package com.spaceboy.ridebuddy.core.navigation

import android.content.Context
import android.location.Address
import android.location.Geocoder
import com.spaceboy.ridebuddy.core.location.RideLocationLabeler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.milliseconds

data class NavigationDestination(val latitude: Double, val longitude: Double, val title: String)

/**
 * Turns whatever a rider shares or pastes into coordinates.
 *
 * The input is unconstrained: a Maps URL with coordinates in it, a shortened Maps link that
 * hides them behind a redirect, a `geo:` URI, a bare "lat,lon" pair, or a plain address.
 *
 * They are tried cheapest-first. Coordinates already present in the text need no network at
 * all. Only a recognised short link is expanded, and only then is geocoding used. Every
 * network step is deadline-bounded, because this runs while a rider is waiting to set off.
 */
class DestinationParser(context: Context) {
    private val appContext = context.applicationContext

    /** Resolves [rawValue] to a destination, or fails with a rider-readable reason. */
    suspend fun parse(rawValue: String): Result<NavigationDestination> = try {
        val value = rawValue.trim()
        val destination = directNavigationDestination(value) ?: run {
            val expanded = if (isGoogleShortLink(value)) {
                expandWithinDeadline(value)
            } else {
                value
            }
            directNavigationDestination(expanded)
                ?: geocode(geocodableText(expanded) ?: throw UnreadableLinkException()).getOrThrow()
        }
        val namedDestination = if (destination.title == "Destination") {
            // Naming a pin is optional; do not hold up a valid route for a slow lookup.
            val name = withTimeoutOrNull(1_500L) {
                RideLocationLabeler(appContext).placeName(destination.latitude, destination.longitude)
            }
            destination.copy(title = name ?: destination.title)
        } else destination
        Result.success(namedDestination)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }

    private fun isGoogleShortLink(value: String): Boolean = runCatching {
        URI(value).host?.lowercase(Locale.ROOT) in ShortLinkHosts
    }.getOrDefault(false)

    private suspend fun expandWithinDeadline(value: String): String =
        withTimeoutOrNull(MaxExpansionMillis) {
            expand(value, System.nanoTime() + MaxExpansionMillis * NanosecondsPerMillisecond)
        } ?: throw DestinationExpansionTimeoutException()

    /**
     * Follows redirects manually to recover the full URL behind a short link.
     *
     * Manually rather than via `instanceFollowRedirects`, because each hop's timeout has to
     * be recomputed from the shared deadline — otherwise a chain of slow redirects could
     * each take the full timeout and blow well past it. The hop count is capped separately
     * against a redirect loop.
     */
    private suspend fun expand(value: String, deadlineNanos: Long): String = withContext(Dispatchers.IO) {
        var current = URL(value)
        repeat(MaxRedirects) {
            currentCoroutineContext().ensureActive()
            val connection = current.openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = remainingExpansionTimeoutMillis(deadlineNanos)
            connection.readTimeout = connection.connectTimeout
            connection.setRequestProperty("User-Agent", "RideBuddy/1")
            val location = try {
                connection.connect()
                currentCoroutineContext().ensureActive()
                connection.readTimeout = remainingExpansionTimeoutMillis(deadlineNanos)
                connection.getHeaderField("Location")
            } finally {
                connection.disconnect()
            }
            if (location.isNullOrBlank()) return@withContext current.toString()
            current = URL(current, location)
        }
        current.toString()
    }

    /**
     * Last resort: ask the platform geocoder to resolve an address.
     *
     * The listener-based API is used because the blocking overload is deprecated and has no
     * timeout of its own. A geocoder error is treated exactly like no result — either way
     * there is no destination, and the rider needs the same message.
     */
    private suspend fun geocode(query: String): Result<NavigationDestination> {
        val geocoder = Geocoder(appContext, Locale.getDefault())
        val address = withTimeoutOrNull(TimeoutMillis.toLong().milliseconds) {
            suspendCancellableCoroutine { continuation ->
                geocoder.getFromLocationName(query, 1, object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<Address>) {
                        if (continuation.isActive) continuation.resume(addresses.firstOrNull())
                    }

                    override fun onError(errorMessage: String?) {
                        if (continuation.isActive) continuation.resume(null)
                    }
                })
            }
        }
        return if (address == null) Result.failure(IllegalArgumentException("Could not find that destination"))
        else Result.success(
            NavigationDestination(
                address.latitude,
                address.longitude,
                address.featureName ?: address.getAddressLine(0) ?: query,
            ),
        )
    }

    private companion object {
        /** Hosts worth a network round trip to expand. Anything else is used as-is. */
        val ShortLinkHosts = setOf("maps.app.goo.gl", "goo.gl")

        /** Redirect hops before giving up, against a loop. */
        const val MaxRedirects = 5

        /** Geocoder deadline. */
        const val TimeoutMillis = 8_000

        /** Total budget for expanding a short link, across all its hops. */
        const val MaxExpansionMillis = 15_000L
        const val NanosecondsPerMillisecond = 1_000_000L
    }
}

internal class DestinationExpansionTimeoutException : IllegalArgumentException(
    "Timed out while opening that shared Maps link",
)

/**
 * A Maps link that resolved but named no place — some shares carry only an internal feature
 * id. Distinct from a failed lookup so the rider is told to reshare rather than to retype.
 */
internal class UnreadableLinkException : IllegalArgumentException(
    "That link doesn't say where to go. In Google Maps, open the place and share it again.",
)

/**
 * Timeout for the next redirect hop: whatever is left of the shared budget, capped.
 *
 * Rounded *up* and floored at 1 ms, because `HttpURLConnection` reads a timeout of zero as
 * "wait forever" — the exact opposite of what an almost-expired deadline means. An expired
 * deadline throws rather than returning a value.
 */
internal fun remainingExpansionTimeoutMillis(
    deadlineNanos: Long,
    nowNanos: Long = System.nanoTime(),
    maximumMillis: Int = 8_000,
): Int {
    val remainingNanos = deadlineNanos - nowNanos
    if (remainingNanos <= 0L) throw DestinationExpansionTimeoutException()
    val roundedUpMillis = (remainingNanos + 999_999L) / 1_000_000L
    return roundedUpMillis.coerceAtMost(maximumMillis.toLong()).toInt().coerceAtLeast(1)
}
