package com.spaceboy.ridebuddy.core.navigation

import android.content.Context
import android.location.Address
import android.location.Geocoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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

class DestinationParser(context: Context) {
    private val appContext = context.applicationContext

    suspend fun parse(rawValue: String): Result<NavigationDestination> = try {
        val value = rawValue.trim()
        val destination = directNavigationDestination(value) ?: run {
            val expanded = if (isGoogleShortLink(value)) expand(value) else value
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

    private suspend fun expand(value: String): String = withContext(Dispatchers.IO) {
        var current = URL(value)
        repeat(MaxRedirects) {
            val connection = current.openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = TimeoutMillis
            connection.readTimeout = TimeoutMillis
            connection.setRequestProperty("User-Agent", "RideBuddy/1")
            val location = try {
                connection.connect()
                connection.getHeaderField("Location")
            } finally {
                connection.disconnect()
            }
            if (location.isNullOrBlank()) return@withContext current.toString()
            current = URL(current, location)
        }
        current.toString()
    }

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
        val ShortLinkHosts = setOf("maps.app.goo.gl", "goo.gl")
        const val MaxRedirects = 5
        const val TimeoutMillis = 8_000
    }
}

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

private fun extractNavigationQuery(value: String): String = runCatching {
    val uri = URI(value)
    val rawQuery = uri.rawQuery.orEmpty().split('&').associate {
        val parts = it.split('=', limit = 2)
        parts.first() to java.net.URLDecoder.decode(parts.getOrElse(1) { "" }, Charsets.UTF_8.name())
    }
    rawQuery["destination"] ?: rawQuery["query"] ?: rawQuery["q"] ?: value
}.getOrDefault(value)

private val CoordinatePatterns = listOf(
    Regex("@(-?\\d{1,2}(?:\\.\\d+)?),(-?\\d{1,3}(?:\\.\\d+)?)"),
    Regex("(?:[?&](?:q|query|destination)=|geo:)(-?\\d{1,2}(?:\\.\\d+)?),(-?\\d{1,3}(?:\\.\\d+)?)"),
    Regex("^\\s*(-?\\d{1,2}(?:\\.\\d+)?)\\s*,\\s*(-?\\d{1,3}(?:\\.\\d+)?)\\s*$"),
)
