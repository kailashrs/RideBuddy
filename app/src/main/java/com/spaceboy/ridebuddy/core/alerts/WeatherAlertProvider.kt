package com.spaceboy.ridebuddy.core.alerts

import com.spaceboy.ridebuddy.core.location.RideLocation
import com.spaceboy.ridebuddy.core.location.RideLocationTracker
import com.spaceboy.ridebuddy.data.AppSettingsRepository
import android.net.Uri
import java.net.HttpURLConnection
import java.util.Locale
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A forecast reading, reduced to the parts that matter on a motorcycle.
 *
 * [weatherCode] is a WMO code as published by the forecast service; the code ranges tested
 * in [riskMessage] are the WMO groupings for thunderstorm, freezing and frozen
 * precipitation, and heavy rain.
 */
data class WeatherSnapshot(
    val weatherCode: Int,
    val precipitationMillimetres: Double,
    val windGustKilometresPerHour: Double,
    val upcomingPrecipitationProbability: Int,
) {
    /**
     * The single most serious warning, or null when nothing is worth raising.
     *
     * The branches are ordered by severity and only the first matching one is reported: a
     * rider gets one clear warning about the worst condition, not a list.
     */
    val riskMessage: String?
        get() = when {
            weatherCode in setOf(95, 96, 99) ->
                "Thunderstorms are forecast nearby. Consider delaying the ride."
            weatherCode in setOf(56, 57) || weatherCode in 66..77 || weatherCode in 85..86 ->
                "Freezing precipitation or snow may make the road slippery."
            weatherCode in setOf(63, 65, 80, 81, 82) || precipitationMillimetres >= 4.0 ->
                "Heavy rain may reduce grip and visibility."
            windGustKilometresPerHour >= 50.0 ->
                "Wind gusts may exceed ${windGustKilometresPerHour.toInt()} km/h."
            upcomingPrecipitationProbability >= 70 ->
                "Rain is likely within the next few hours (${upcomingPrecipitationProbability}%)."
            else -> null
        }
}

/**
 * Polls a forecast for the rider's current area and reports conditions worth knowing about.
 *
 * Refreshes are deliberately sparse — this runs on a phone on a bike, and the weather at a
 * given point does not change minute to minute. A fetch happens only after enough time has
 * passed *or* the rider has moved far enough to be somewhere with different weather, and a
 * failed attempt is separately rate-limited so a lost connection does not turn into a
 * retry loop.
 */
class WeatherAlertProvider(
    private val locationTracker: RideLocationTracker,
    private val settings: AppSettingsRepository,
    private val scope: CoroutineScope,
    private val onRisk: (String) -> Unit,
) {
    private var lastCheckedAtMillis = 0L
    private var lastCheckedLocation: RideLocation? = null
    private var lastAttemptAtMillis = 0L

    fun start() {
        scope.launch {
            combine(locationTracker.location, settings.settings) { _, preferences ->
                locationTracker.freshLocation().takeIf { preferences.weatherAlerts }
            }.collect { location ->
                location ?: return@collect
                val now = System.currentTimeMillis()
                if (!shouldRefresh(location, now)) return@collect
                lastAttemptAtMillis = now
                val snapshot = try {
                    fetch(location)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                snapshot?.also {
                    lastCheckedAtMillis = now
                    lastCheckedLocation = location
                }?.riskMessage?.let(onRisk)
            }
        }
    }

    /**
     * Whether to fetch now. The attempt guard comes first and applies to failures too, so
     * an unreachable service is retried on its own slower schedule rather than on every
     * location update.
     */
    private fun shouldRefresh(location: RideLocation, now: Long): Boolean {
        if (now - lastAttemptAtMillis < RetryIntervalMillis) return false
        val previous = lastCheckedLocation ?: return true
        return now - lastCheckedAtMillis >= RefreshIntervalMillis || distanceKilometres(previous, location) >= RefreshDistanceKilometres
    }

    private suspend fun fetch(location: RideLocation): WeatherSnapshot = withContext(Dispatchers.IO) {
        val endpoint = URL(forecastUrl(location.latitude, location.longitude))
        val connection = endpoint.openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = NetworkTimeoutMillis
            connection.readTimeout = NetworkTimeoutMillis
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json")
            check(connection.responseCode in 200..299) { "Weather request failed (${connection.responseCode})" }
            parse(JSONObject(connection.inputStream.bufferedReader().use { it.readText() }))
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Reads the response leniently: every field has a default, so a service that drops or
     * renames one degrades to "no warning" rather than throwing. The hourly probability is
     * reduced to its maximum across the forecast window — the rider needs to know rain is
     * coming, not which hour it lands in.
     */
    private fun parse(json: JSONObject): WeatherSnapshot {
        val current = json.getJSONObject("current")
        val probabilities = json.optJSONObject("hourly")
            ?.optJSONArray("precipitation_probability")
        val highestProbability = if (probabilities == null) 0 else {
            (0 until probabilities.length()).maxOfOrNull { probabilities.optInt(it, 0) } ?: 0
        }
        return WeatherSnapshot(
            weatherCode = current.optInt("weather_code", 0),
            precipitationMillimetres = current.optDouble("precipitation", 0.0),
            windGustKilometresPerHour = current.optDouble("wind_gusts_10m", 0.0),
            upcomingPrecipitationProbability = highestProbability,
        )
    }

    /** Great-circle distance (haversine). Only used against a coarse threshold. */
    private fun distanceKilometres(first: RideLocation, second: RideLocation): Double {
        val latitudeDelta = Math.toRadians(second.latitude - first.latitude)
        val longitudeDelta = Math.toRadians(second.longitude - first.longitude)
        val firstLatitude = Math.toRadians(first.latitude)
        val secondLatitude = Math.toRadians(second.latitude)
        val a = sin(latitudeDelta / 2) * sin(latitudeDelta / 2) +
            cos(firstLatitude) * cos(secondLatitude) * sin(longitudeDelta / 2) * sin(longitudeDelta / 2)
        return EarthRadiusKilometres * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    private companion object {
        /** Time between successful fetches from the same area. */
        const val RefreshIntervalMillis = 30 * 60 * 1_000L

        /** Minimum gap between attempts, successful or not. Bounds retries on failure. */
        const val RetryIntervalMillis = 5 * 60 * 1_000L

        /** Distance after which the forecast is refetched regardless of elapsed time. */
        const val RefreshDistanceKilometres = 10.0

        const val NetworkTimeoutMillis = 10_000
        const val EarthRadiusKilometres = 6_371.0
    }
}

/**
 * The forecast request for one position.
 *
 * Built with [Uri.Builder] rather than by concatenation so the values are encoded rather
 * than trusted to contain nothing that needs it.
 */
internal fun forecastUrl(latitude: Double, longitude: Double): String = Uri.Builder()
    .scheme("https")
    .authority("api.open-meteo.com")
    .appendEncodedPath("v1/forecast")
    .appendQueryParameter("latitude", latitude.asCoordinate())
    .appendQueryParameter("longitude", longitude.asCoordinate())
    .appendQueryParameter("current", "weather_code,precipitation,wind_gusts_10m")
    .appendQueryParameter("hourly", "precipitation_probability")
    .appendQueryParameter("forecast_hours", "3")
    .appendQueryParameter("timezone", "auto")
    .build()
    .toString()

/**
 * Fixed point, because `Double.toString` switches to scientific notation below a thousandth:
 * a position within about a hundred metres of the equator or the prime meridian would have
 * been sent as `1.0E-5`, and nothing promises the service reads that as a number. Six places
 * is roughly a tenth of a metre, far finer than a forecast grid.
 */
internal fun Double.asCoordinate(): String = String.format(Locale.US, "%.6f", this)
