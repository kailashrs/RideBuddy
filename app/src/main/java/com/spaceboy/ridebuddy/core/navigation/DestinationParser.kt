package com.spaceboy.ridebuddy.core.navigation

import android.content.Context
import android.location.Address
import android.location.Geocoder
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
import java.net.URLDecoder
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
            directNavigationDestination(expanded) ?: geocode(expanded).getOrThrow()
        }
        Result.success(destination)
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
    private suspend fun geocode(value: String): Result<NavigationDestination> {
        val query = extractNavigationQuery(value)
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

/**
 * Coordinates recoverable from the text without any network access.
 *
 * Tried twice: once against the raw text, and once against the URL's decoded query
 * parameter, since a shared link often carries the coordinates percent-encoded inside it.
 */
internal fun directNavigationDestination(value: String): NavigationDestination? =
    coordinateFromText(value) ?: coordinateFromText(extractNavigationQuery(value))

private fun coordinateFromText(value: String): NavigationDestination? {
    CoordinatePatterns.forEach { pattern ->
        pattern.find(value)?.let { match ->
            val latitude = match.groupValues[1].toDoubleOrNull() ?: return@let
            val longitude = match.groupValues[2].toDoubleOrNull() ?: return@let
            if (latitude in -90.0..90.0 && longitude in -180.0..180.0) {
                return NavigationDestination(latitude, longitude, "Shared destination")
            }
        }
    }
    return null
}

/**
 * The destination parameter out of a Maps-style URL, or the input unchanged when it is not
 * a URL — which is the normal case for a typed address.
 */
private fun extractNavigationQuery(value: String): String = runCatching {
    val uri = URI(value)
    val rawQuery = uri.rawQuery.orEmpty().split('&').associate {
        val parts = it.split('=', limit = 2)
        parts.first() to URLDecoder.decode(parts.getOrElse(1) { "" }, Charsets.UTF_8.name())
    }
    rawQuery["destination"] ?: rawQuery["query"] ?: rawQuery["q"] ?: value
}.getOrDefault(value)

// Ordered most specific first: the Maps `@lat,lon` viewport marker, then a coordinate
// carried in a query parameter or `geo:` URI, then a bare pair on its own. The digit
// bounds keep them from matching arbitrary numbers elsewhere in a URL, and every match is
// range-checked before it is accepted.
private val CoordinatePatterns = listOf(
    Regex("@(-?\\d{1,2}(?:\\.\\d+)?),(-?\\d{1,3}(?:\\.\\d+)?)"),
    Regex("(?:[?&](?:q|query|destination)=|geo:)(-?\\d{1,2}(?:\\.\\d+)?),(-?\\d{1,3}(?:\\.\\d+)?)"),
    Regex("^\\s*(-?\\d{1,2}(?:\\.\\d+)?)\\s*,\\s*(-?\\d{1,3}(?:\\.\\d+)?)\\s*$"),
)
