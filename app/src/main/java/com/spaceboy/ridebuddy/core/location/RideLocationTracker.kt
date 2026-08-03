package com.spaceboy.ridebuddy.core.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class RideLocation(
    val latitude: Double,
    val longitude: Double,
    val accuracyMetres: Float,
    val altitudeMetres: Double?,
)

class RideLocationTracker(context: Context) : LocationListener {
    private val manager = context.getSystemService(LocationManager::class.java)
    private val appContext = context.applicationContext
    private val mutableLocation = MutableStateFlow<RideLocation?>(null)
    val location: StateFlow<RideLocation?> = mutableLocation.asStateFlow()

    @SuppressLint("MissingPermission")
    fun start() {
        stop()
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val provider = when {
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> return
        }
        manager.requestLocationUpdates(provider, 2_000L, 3f, this)
    }

    fun stop() = manager.removeUpdates(this)

    override fun onLocationChanged(location: Location) {
        mutableLocation.value = RideLocation(
            latitude = location.latitude,
            longitude = location.longitude,
            accuracyMetres = location.accuracy,
            altitudeMetres = location.altitude.takeIf { location.hasAltitude() },
        )
    }
}
