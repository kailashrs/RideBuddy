package com.spaceboy.ridebuddy.core.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One GPS fix.
 *
 * [fixElapsedRealtimeMillis] is when the *fix* was taken, on the monotonic clock, not when
 * it was delivered. That distinction is what freshness checks depend on: the platform can
 * hand over a fix that is minutes old, and wall-clock time can jump.
 */
data class RideLocation(
    val latitude: Double,
    val longitude: Double,
    val accuracyMetres: Float,
    val altitudeMetres: Double?,
    val fixElapsedRealtimeMillis: Long,
)

/**
 * Supplies GPS fixes for route recording, weather lookups, and ride labelling.
 *
 * Uses the platform location manager directly rather than a fused provider, so the app has
 * no dependency on Play services for a feature that is only meaningful outdoors and moving.
 */
class RideLocationTracker(context: Context) : LocationListener {
    private val manager = context.getSystemService(LocationManager::class.java)
    private val appContext = context.applicationContext
    private val mutableLocation = MutableStateFlow<RideLocation?>(null)
    val location: StateFlow<RideLocation?> = mutableLocation.asStateFlow()

    /**
     * Route recording only accepts GPS fixes; a network fix is far too coarse to integrate into a
     * ride trace, so there is no fallback provider. The registration outlives the provider being
     * switched off mid-ride, so updates resume by themselves once GPS is back.
     */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        stop()
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        val request = LocationRequest.Builder(LocationUpdateIntervalMillis)
            .setMinUpdateDistanceMeters(MinimumLocationDistanceMetres)
            .setQuality(LocationRequest.QUALITY_HIGH_ACCURACY)
            .build()
        manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, request, appContext.mainExecutor, this)
        manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let(::onLocationChanged)
        return true
    }

    /** Unregisters and clears the published fix, so nothing reads a stale position. */
    fun stop() {
        manager.removeUpdates(this)
        mutableLocation.value = null
    }

    /**
     * The current fix if it is recent enough to act on, or null. Callers wanting whatever
     * is last known regardless of age should read [location] instead.
     */
    fun freshLocation(nowElapsedRealtimeMillis: Long = SystemClock.elapsedRealtime()): RideLocation? =
        mutableLocation.value?.takeIf { it.isFreshAt(nowElapsedRealtimeMillis) }

    override fun onLocationChanged(location: Location) {
        mutableLocation.value = RideLocation(
            latitude = location.latitude,
            longitude = location.longitude,
            accuracyMetres = location.accuracy,
            altitudeMetres = location.altitude.takeIf { location.hasAltitude() },
            fixElapsedRealtimeMillis = location.elapsedRealtimeNanos / NanosecondsPerMillisecond,
        )
    }

    /** A disabled provider invalidates the fix but keeps the registration for when it returns. */
    override fun onProviderDisabled(provider: String) {
        mutableLocation.value = null
    }

    private companion object {
        /** Fast enough for a usable route trace without pinning the GPS at maximum rate. */
        const val LocationUpdateIntervalMillis = 2_000L

        /** Suppresses updates while parked, where GPS jitter would otherwise fill the trace. */
        const val MinimumLocationDistanceMetres = 3f

        const val NanosecondsPerMillisecond = 1_000_000L
    }
}

/** Whether a fix is recent enough to act on. */
internal fun RideLocation.isFreshAt(
    nowElapsedRealtimeMillis: Long,
    maximumAgeMillis: Long = MaximumRideLocationAgeMillis,
): Boolean = nowElapsedRealtimeMillis - fixElapsedRealtimeMillis <= maximumAgeMillis

/** Generous, because the consumers are weather and labelling rather than live positioning. */
internal const val MaximumRideLocationAgeMillis = 30_000L
