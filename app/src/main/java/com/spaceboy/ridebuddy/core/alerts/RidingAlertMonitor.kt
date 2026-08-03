package com.spaceboy.ridebuddy.core.alerts

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import com.spaceboy.ridebuddy.R
import com.spaceboy.ridebuddy.data.AppSettingsRepository
import com.spaceboy.ridebuddy.data.RideRecorder
import com.spaceboy.ridebuddy.data.UnitFormatter
import com.spaceboy.ridebuddy.domain.BikeConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale

class RidingAlertMonitor(
    context: Context,
    private val connection: BikeConnection,
    private val rideRecorder: RideRecorder,
    private val settings: AppSettingsRepository,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private val notifications = appContext.getSystemService(NotificationManager::class.java)
    private val lastAlertAt = mutableMapOf<String, Long>()

    fun start() {
        createChannel()
        scope.launch {
            connection.telemetry.collectLatest { frame ->
                frame ?: return@collectLatest
                val preferences = settings.settings.value
                if (preferences.overspeedAlerts && frame.speedKilometresPerHour >= preferences.overspeedThresholdKph) {
                    alert(
                        "speed",
                        "Speed alert",
                        "Current speed is above ${UnitFormatter.speed(preferences.overspeedThresholdKph.toDouble(), preferences.distanceUnits, Locale.getDefault())}",
                    )
                }
                if (preferences.rpmAlerts && frame.engineRpm >= preferences.rpmThreshold) {
                    alert("rpm", "Engine speed alert", "Engine speed is above ${preferences.rpmThreshold} rpm")
                }
            }
        }
        scope.launch {
            rideRecorder.liveSamples.collectLatest { samples ->
                val acceleration = samples.lastOrNull()?.accelerationMetresPerSecondSquared ?: return@collectLatest
                val preferences = settings.settings.value
                if (preferences.accelerationAlerts && acceleration >= 3.0) {
                    alert("acceleration", "Hard acceleration", "Acceleration reached %.1f m/s²".format(acceleration))
                }
                if (preferences.brakingAlerts && acceleration <= -3.5) {
                    alert("braking", "Hard braking", "Deceleration reached %.1f m/s²".format(acceleration))
                }
            }
        }
    }

    fun navigationHazard(message: String): Boolean =
        settings.settings.value.hazardAlerts && alert("navigation_hazard", "Route alert", message)

    fun weatherAlert(message: String): Boolean = settings.settings.value.weatherAlerts &&
        alert("weather", "Riding weather", "$message Weather data by Open-Meteo.com.")

    private fun alert(key: String, title: String, message: String): Boolean {
        val now = System.currentTimeMillis()
        val canAlert = synchronized(lastAlertAt) {
            if (now - (lastAlertAt[key] ?: 0L) < AlertCooldownMillis) false
            else {
                lastAlertAt[key] = now
                true
            }
        }
        if (!canAlert) return false
        notifications.notify(
            key.hashCode(),
            NotificationCompat.Builder(appContext, ChannelId)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(title)
                .setContentText(message)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build(),
        )
        return true
    }

    private fun createChannel() {
        notifications.createNotificationChannel(
            NotificationChannel(ChannelId, "Riding alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Optional speed, engine, riding, route and weather warnings"
            },
        )
    }

    private companion object {
        const val ChannelId = "riding_alerts"
        const val AlertCooldownMillis = 30_000L
    }
}
