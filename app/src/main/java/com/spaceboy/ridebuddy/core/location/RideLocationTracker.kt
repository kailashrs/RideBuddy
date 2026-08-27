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
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class RideLocation(
    val latitude: Double,
    val longitude: Double,
    val accuracyMetres: Float,
    val altitudeMetres: Double?,
    val fixElapsedRealtimeMillis: Long,
)

class RideLocationTracker(context: Context) : LocationListener {
    private val manager = context.getSystemService(LocationManager::class.java)
    private val appContext = context.applicationContext
    private val mutableLocation = MutableStateFlow<RideLocation?>(null)
    val location: StateFlow<RideLocation?> = mutableLocation.asStateFlow()
    private var registered = false

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
        return runCatching {
            val request = LocationRequest.Builder(LocationUpdateIntervalMillis)
                .setMinUpdateDistanceMeters(MinimumLocationDistanceMetres)
                .setQuality(LocationRequest.QUALITY_HIGH_ACCURACY)
                .build()
            manager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                request,
                appContext.mainExecutor,
                this,
            )
            registered = true
            manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let(::onLocationChanged)
            true
        }.onFailure { error ->
            Log.w(LogTag, "Could not start ride location updates", error)
            registered = false
        }.getOrDefault(false)
    }

    fun stop() {
        manager.removeUpdates(this)
        registered = false
        mutableLocation.value = null
    }

    fun freshLocation(nowElapsedRealtimeMillis: Long = SystemClock.elapsedRealtime()): RideLocation? =
        mutableLocation.value?.takeIf { it.isFreshAt(nowElapsedRealtimeMillis) }

    override fun onLocationChanged(location: Location) {
        mutableLocation.value = RideLocation(
            latitude = location.latitude,
            longitude = location.longitude,
            accuracyMetres = location.accuracy,
            altitudeMetres = location.altitude.takeIf { location.hasAltitude() },
            fixElapsedRealtimeMillis = location.elapsedRealtimeNanos
                .takeIf { it > 0L }
                ?.div(NanosecondsPerMillisecond)
                ?: SystemClock.elapsedRealtime(),
        )
    }

    /** A disabled provider invalidates the fix but keeps the registration for when it returns. */
    override fun onProviderDisabled(provider: String) {
        if (registered && provider == LocationManager.GPS_PROVIDER) mutableLocation.value = null
    }

    private companion object {
        const val LogTag = "RideLocationTracker"
        const val LocationUpdateIntervalMillis = 2_000L
        const val MinimumLocationDistanceMetres = 3f
        const val NanosecondsPerMillisecond = 1_000_000L
    }
}

internal fun RideLocation.isFreshAt(
    nowElapsedRealtimeMillis: Long,
    maximumAgeMillis: Long = MaximumRideLocationAgeMillis,
): Boolean {
    val ageMillis = nowElapsedRealtimeMillis - fixElapsedRealtimeMillis
    return fixElapsedRealtimeMillis > 0L && ageMillis in 0..maximumAgeMillis
}

internal const val MaximumRideLocationAgeMillis = 30_000L
