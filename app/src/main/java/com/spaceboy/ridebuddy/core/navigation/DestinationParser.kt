package com.spaceboy.ridebuddy.core.navigation

import com.spaceboy.ridebuddy.core.location.awaitAddress
import com.spaceboy.ridebuddy.core.location.RideLocationLabeler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Locale

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
class DestinationParser(private val labeler: RideLocationLabeler) {

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
                labeler.placeName(destination.latitude, destination.longitude)
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

    /**
     * The full URL behind a short link. The platform follows the redirects; one deadline bounds
     * the whole chain, and each connection's own timeouts bound a single stalled hop.
     */
    private suspend fun expandWithinDeadline(value: String): String = withTimeoutOrNull(MaxExpansionMillis) {
        runInterruptible(Dispatchers.IO) {
            val connection = URL(value).openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = true
                connection.connectTimeout = HopTimeoutMillis
                connection.readTimeout = HopTimeoutMillis
                connection.setRequestProperty("User-Agent", "RideBuddy/1")
                connection.responseCode
                connection.url.toString()
            } finally {
                connection.disconnect()
            }
        }
    } ?: throw DestinationExpansionTimeoutException()

    /**
     * Last resort: ask the platform geocoder to resolve an address.
     *
     * The listener-based API is used because the blocking overload is deprecated and has no
     * timeout of its own. A geocoder error is treated exactly like no result — either way
     * there is no destination, and the rider needs the same message.
     */
    private suspend fun geocode(query: String): Result<NavigationDestination> {
        val address = labeler.geocoder.awaitAddress(TimeoutMillis) { listener ->
            getFromLocationName(query, 1, listener)
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

        /** Geocoder deadline. */
        const val TimeoutMillis = 8_000L

        /** Total budget for expanding a short link, across all its hops. */
        const val MaxExpansionMillis = 15_000L
        const val HopTimeoutMillis = 8_000
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
