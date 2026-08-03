package com.spaceboy.ridebuddy.core.navigation

import android.content.Context
import android.location.Geocoder
import android.os.Build
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

data class NavigationDestination(val latitude: Double, val longitude: Double, val title: String)

class DestinationParser(context: Context) {
    private val appContext = context.applicationContext

    suspend fun parse(rawValue: String): Result<NavigationDestination> = try {
        val value = rawValue.trim()
        val destination = coordinateFrom(value) ?: run {
            val expanded = if (isGoogleShortLink(value)) expand(value) else value
            coordinateFrom(expanded) ?: geocode(expanded).getOrThrow()
        }
        Result.success(destination)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }

    private fun coordinateFrom(value: String): NavigationDestination? {
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
            connection.setRequestProperty("User-Agent", "RS457Companion/1")
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
        val query = extractQuery(value)
        val geocoder = Geocoder(appContext, Locale.getDefault())
        val address = withTimeoutOrNull(TimeoutMillis.toLong()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                suspendCancellableCoroutine { continuation ->
                    geocoder.getFromLocationName(query, 1) { results ->
                        if (continuation.isActive) continuation.resume(results.firstOrNull())
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                withContext(Dispatchers.IO) { geocoder.getFromLocationName(query, 1)?.firstOrNull() }
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

    private fun extractQuery(value: String): String = runCatching {
        val uri = URI(value)
        val rawQuery = uri.rawQuery.orEmpty().split('&').associate {
            val parts = it.split('=', limit = 2)
            parts.first() to java.net.URLDecoder.decode(parts.getOrElse(1) { "" }, Charsets.UTF_8.name())
        }
        rawQuery["destination"] ?: rawQuery["query"] ?: rawQuery["q"] ?: value
    }.getOrDefault(value)

    private companion object {
        val CoordinatePatterns = listOf(
            Regex("@(-?\\d{1,2}(?:\\.\\d+)?),(-?\\d{1,3}(?:\\.\\d+)?)"),
            Regex("(?:[?&](?:q|query|destination)=|geo:)(-?\\d{1,2}(?:\\.\\d+)?),(-?\\d{1,3}(?:\\.\\d+)?)"),
            Regex("^\\s*(-?\\d{1,2}(?:\\.\\d+)?)\\s*,\\s*(-?\\d{1,3}(?:\\.\\d+)?)\\s*$"),
        )
        val ShortLinkHosts = setOf("maps.app.goo.gl", "goo.gl")
        const val MaxRedirects = 5
        const val TimeoutMillis = 8_000
    }
}
