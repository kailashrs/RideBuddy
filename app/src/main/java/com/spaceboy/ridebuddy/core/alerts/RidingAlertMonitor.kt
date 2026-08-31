package com.spaceboy.ridebuddy.core.alerts

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.spaceboy.ridebuddy.R
import com.spaceboy.ridebuddy.data.AppSettingsRepository
import com.spaceboy.ridebuddy.data.RideRecorder
import com.spaceboy.ridebuddy.data.UnitFormatter
import com.spaceboy.ridebuddy.domain.BikeConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Raises the rider's opt-in warnings: speed, engine speed, hard acceleration and braking,
 * route hazards, and weather.
 *
 * Every alert is off by default and rate-limited per kind. The cooldown is what makes these
 * usable at all — the conditions that trigger them persist for many seconds, so an
 * un-throttled check running at telemetry rate would produce a continuous stream of
 * notifications for one event.
 */
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

    /** Creates the notification channel and starts watching every alert source. */
    fun start() {
        createChannel()
        scope.launch {
            // The body never suspends, so collectLatest only bought four coroutine
            // cancellations a second.
            connection.telemetry.collect { frame ->
                frame ?: return@collect
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
            rideRecorder.liveSampleEvents.collect { sample ->
                val acceleration = sample.accelerationMetresPerSecondSquared
                val preferences = settings.settings.value
                when (ridingMotionAlert(acceleration, preferences.accelerationAlerts, preferences.brakingAlerts)) {
                    RidingMotionAlert.HardAcceleration ->
                        alert("acceleration", "Hard acceleration", "Acceleration reached %.1f m/s²".format(acceleration))
                    RidingMotionAlert.HardBraking ->
                        alert("braking", "Hard braking", "Deceleration reached %.1f m/s²".format(acceleration))
                    null -> Unit
                }
            }
        }
    }

    /** Route hazard reported by the navigation provider. Returns whether it was raised. */
    fun navigationHazard(message: String): Boolean =
        settings.settings.value.hazardAlerts && alert("navigation_hazard", "Route alert", message)

    /** Weather warning. The attribution is required by the forecast data's licence. */
    fun weatherAlert(message: String): Boolean = settings.settings.value.weatherAlerts &&
        alert("weather", "Riding weather", "$message Weather data by Open-Meteo.com.")

    /**
     * Returns whether this alert cleared its cooldown, which is what decides whether the caller
     * also routes it to the TFT. Posting the phone notification is a separate concern: with
     * notification access denied it is skipped, but the alert itself still counts as raised.
     */
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
        if (!NotificationManagerCompat.from(appContext).areNotificationsEnabled()) return true
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

        /** Per-kind quiet period. Long enough that one sustained event raises one alert. */
        const val AlertCooldownMillis = 30_000L
    }
}

internal enum class RidingMotionAlert { HardAcceleration, HardBraking }

/**
 * Classifies a longitudinal acceleration sample.
 *
 * The braking threshold is the larger of the two in magnitude, because a bike decelerates
 * far harder than it accelerates: symmetric thresholds would fire on ordinary braking.
 * Values are in m/s², derived from wheel speed, so they are unsigned in the sense that only
 * the sign distinguishes the two cases.
 */
internal fun ridingMotionAlert(
    accelerationMetresPerSecondSquared: Double,
    accelerationAlertsEnabled: Boolean,
    brakingAlertsEnabled: Boolean,
): RidingMotionAlert? = when {
    accelerationAlertsEnabled && accelerationMetresPerSecondSquared >= 3.0 -> RidingMotionAlert.HardAcceleration
    brakingAlertsEnabled && accelerationMetresPerSecondSquared <= -3.5 -> RidingMotionAlert.HardBraking
    else -> null
}
