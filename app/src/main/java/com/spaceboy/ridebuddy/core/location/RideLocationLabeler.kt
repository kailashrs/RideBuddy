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
class RideLocationLabeler internal constructor(private val geocoder: Geocoder) {
    constructor(context: Context) : this(Geocoder(context.applicationContext, Locale.getDefault()))

    /** Coordinates are a last resort; a later visit to the ride can retry the name lookup. */
    suspend fun label(latitude: Double?, longitude: Double?): String? {
        if (latitude == null || longitude == null) return null
        return placeName(latitude, longitude) ?: "%.4f, %.4f".format(Locale.US, latitude, longitude)
    }

    /** Optional enrichment: failure must not prevent navigation to known coordinates. */
    suspend fun placeName(latitude: Double, longitude: Double): String? {
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
        return address?.shortPlaceLabel()
    }

    private companion object {
        const val GeocoderTimeoutMillis = 5_000L
    }
}

/** Some providers return a road or address without locality fields. Use it before coordinates. */
internal fun Address.shortPlaceLabel(): String? {
    val area = listOfNotNull(subLocality, locality, adminArea)
        .filter(String::isNotBlank).distinct().take(2).joinToString(", ")
    return area.takeIf(String::isNotBlank)
        ?: thoroughfare?.takeIf(String::isNotBlank)
        ?: featureName?.takeIf { it.isNotBlank() && it.any(Char::isLetter) }
        ?: getAddressLine(0)?.takeIf(String::isNotBlank)
}

internal fun needsPlaceName(label: String?): Boolean = label.isNullOrBlank() ||
    Regex("^-?\\d{1,2}\\.\\d+, -?\\d{1,3}\\.\\d+$").matches(label)
