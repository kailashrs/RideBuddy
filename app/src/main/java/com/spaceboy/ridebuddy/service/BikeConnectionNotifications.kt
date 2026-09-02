package com.spaceboy.ridebuddy.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.spaceboy.ridebuddy.MainActivity
import com.spaceboy.ridebuddy.R
import com.spaceboy.ridebuddy.domain.BikeConnectionState

/**
 * Builds and publishes the foreground-service notification.
 *
 * Split out of [BikeConnectionService] so the service is left with command routing and lifecycle
 * only. The Disconnect action targets [BikeConnectionActionReceiver] rather than the service, so
 * tapping it never starts a foreground service from the background.
 */
internal class BikeConnectionNotifications(private val context: Context) {
    private val notificationManager = context.getSystemService(NotificationManager::class.java)

    fun createChannel() {
        notificationManager.createNotificationChannel(
            NotificationChannel(ChannelId, "Bike connection", NotificationManager.IMPORTANCE_LOW),
        )
    }

    /** The ongoing notification. Low importance: it is a status line, not an alert. */
    fun build(status: String) = NotificationCompat.Builder(context, ChannelId)
        .setSmallIcon(R.drawable.ic_launcher)
        .setContentTitle("RideBuddy")
        .setContentText(status)
        .setOngoing(true)
        .setContentIntent(
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        .addAction(
            0,
            "Disconnect",
            PendingIntent.getBroadcast(
                context,
                1,
                Intent(context, BikeConnectionActionReceiver::class.java)
                    .setAction(BikeConnectionService.ActionDisconnect),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        .build()

    /**
     * Updates the notification in place. Failures are logged rather than thrown: notification
     * posting can be refused, and losing a status line must not take the connection down.
     */
    fun publish(status: String) {
        runCatching { notificationManager.notify(BikeConnectionService.NotificationId, build(status)) }
            .onFailure { error ->
                Log.w("BikeConnectionService", "Unable to update connection notification", error)
            }
    }

    fun cancel() {
        notificationManager.cancel(BikeConnectionService.NotificationId)
    }

    private companion object {
        const val ChannelId = "bike_connection"
    }
}

/** The status line shown on the foreground notification. */
internal fun connectionNotificationStatus(
    state: BikeConnectionState,
    locationPermissionMissing: Boolean,
): String {
    val connectionLabel = when (state) {
        is BikeConnectionState.Connected -> "Connected to ${state.deviceName}"
        // Connecting and authenticating read the same on a lock screen, and the retry count is
        // the app's own bookkeeping — nobody waits differently on attempt four than on attempt two.
        is BikeConnectionState.Connecting -> "Connecting to ${state.deviceName ?: "your motorcycle"}"
        is BikeConnectionState.Authenticating -> "Connecting to ${state.deviceName}"
        is BikeConnectionState.Failed -> state.message
        BikeConnectionState.Disconnected -> "Disconnected"
    }
    return if (locationPermissionMissing && state is BikeConnectionState.Connected) {
        "$connectionLabel — location needs permission"
    } else {
        connectionLabel
    }
}
