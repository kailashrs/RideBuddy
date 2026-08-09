package com.spaceboy.ridebuddy.core.alerts

import com.spaceboy.ridebuddy.core.location.RideLocation
import com.spaceboy.ridebuddy.core.location.RideLocationTracker
import com.spaceboy.ridebuddy.data.AppSettingsRepository
import java.net.HttpURLConnection
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

data class WeatherSnapshot(
    val weatherCode: Int,
    val precipitationMillimetres: Double,
    val windGustKilometresPerHour: Double,
    val upcomingPrecipitationProbability: Int,
) {
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

    private fun shouldRefresh(location: RideLocation, now: Long): Boolean {
        if (now - lastAttemptAtMillis < RetryIntervalMillis) return false
        val previous = lastCheckedLocation ?: return true
        return now - lastCheckedAtMillis >= RefreshIntervalMillis || distanceKilometres(previous, location) >= RefreshDistanceKilometres
    }

    private suspend fun fetch(location: RideLocation): WeatherSnapshot = withContext(Dispatchers.IO) {
        val endpoint = URL(
            "https://api.open-meteo.com/v1/forecast" +
                "?latitude=${location.latitude}&longitude=${location.longitude}" +
                "&current=weather_code,precipitation,wind_gusts_10m" +
                "&hourly=precipitation_probability&forecast_hours=3&timezone=auto",
        )
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
        const val RefreshIntervalMillis = 30 * 60 * 1_000L
        const val RetryIntervalMillis = 5 * 60 * 1_000L
        const val RefreshDistanceKilometres = 10.0
        const val NetworkTimeoutMillis = 10_000
        const val EarthRadiusKilometres = 6_371.0
    }
}
