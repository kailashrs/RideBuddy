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
class RideLocationLabeler(val geocoder: Geocoder) {
    constructor(context: Context) : this(Geocoder(context.applicationContext, Locale.getDefault()))

    /** Coordinates are a last resort; a later visit to the ride can retry the name lookup. */
    suspend fun label(latitude: Double?, longitude: Double?): String? {
        if (latitude == null || longitude == null) return null
        return placeName(latitude, longitude) ?: "%.4f, %.4f".format(Locale.US, latitude, longitude)
    }

    /** Optional enrichment: a failed or slow lookup yields null rather than an error. */
    suspend fun placeName(latitude: Double, longitude: Double): String? =
        geocoder.awaitAddress(GeocoderTimeoutMillis) { listener -> getFromLocation(latitude, longitude, 1, listener) }
            ?.shortPlaceLabel()

    /**
     * The first line of the address at a point — "12 Anna Salai" rather than the area around it —
     * for naming a destination, which is one door rather than a neighbourhood.
     */
    suspend fun addressFirstLine(latitude: Double, longitude: Double): String? =
        geocoder.awaitAddress(GeocoderTimeoutMillis) { listener -> getFromLocation(latitude, longitude, 1, listener) }
            ?.firstAddressLine()
}

internal fun Address.firstAddressLine(): String? =
    featureName?.takeIf { name -> name.any(Char::isLetter) && name != thoroughfare }
        ?: listOfNotNull(subThoroughfare, thoroughfare).filter(String::isNotBlank).joinToString(" ").takeIf(String::isNotBlank)
        ?: getAddressLine(0)?.substringBefore(',')?.trim()?.takeIf(String::isNotBlank)
        ?: shortPlaceLabel()

/**
 * The first address from one of the listener-based [Geocoder] calls, or null on error, no
 * result, or [timeoutMillis]. The blocking overloads are deprecated and have no timeout.
 */
internal suspend fun Geocoder.awaitAddress(
    timeoutMillis: Long,
    request: Geocoder.(Geocoder.GeocodeListener) -> Unit,
): Address? = try {
    withTimeoutOrNull(timeoutMillis.milliseconds) {
        suspendCancellableCoroutine { continuation ->
            request(object : Geocoder.GeocodeListener {
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

private const val GeocoderTimeoutMillis = 5_000L

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
