package com.spaceboy.ridebuddy.core.location

import android.content.Context
import android.location.Address
import android.location.Geocoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.milliseconds

/**
 * Turns coordinates into a short place name for ride history — "Camden, London" rather
 * than a latitude and longitude the rider has to decode.
 */
class RideLocationLabeler(context: Context) {
    private val geocoder = Geocoder(context.applicationContext, Locale.getDefault())

    /**
     * A place name for the given point.
     *
     * Falls back to formatted coordinates rather than null when the geocoder is
     * unavailable, times out, or returns nothing: reverse geocoding needs a network, and a
     * ride recorded out of coverage should still get a label it can be identified by.
     */
    suspend fun label(latitude: Double?, longitude: Double?): String? {
        if (latitude == null || longitude == null) return null
        val address = try {
            withTimeoutOrNull(GeocoderTimeoutMillis.milliseconds) {
                suspendCancellableCoroutine<Address?> { continuation ->
                    geocoder.getFromLocation(latitude, longitude, 1, object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<Address>) {
                            if (continuation.isActive) continuation.resume(addresses.firstOrNull())
                        }

                        override fun onError(errorMessage: String?) {
                            if (continuation.isActive) continuation.resume(null)
                        }
                    })
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        return address?.shortLabel() ?: "%.4f, %.4f".format(Locale.US, latitude, longitude)
    }

    /**
     * Two levels at most, narrowest first. A full address line is far too long for a
     * history row, and the neighbourhood plus the town is what identifies a ride.
     */
    private fun Address.shortLabel(): String? = listOfNotNull(subLocality, locality, adminArea)
        .distinct()
        .take(2)
        .joinToString(", ")
        .takeIf(String::isNotBlank)

    private companion object {
        const val GeocoderTimeoutMillis = 5_000L
    }
}
