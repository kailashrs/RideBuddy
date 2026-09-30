package com.spaceboy.ridebuddy.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.SystemClock
import com.spaceboy.ridebuddy.ble.BleCharacteristics
import com.spaceboy.ridebuddy.data.AppSettings
import com.spaceboy.ridebuddy.data.SupportedNotificationApp
import com.spaceboy.ridebuddy.domain.BikeConnection
import com.spaceboy.ridebuddy.domain.BikeConnectionState
import com.spaceboy.ridebuddy.domain.BikeControlEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Notification-icon packet on `8110`: `[0x0B, event, phone battery percent, 0x00]`. */
internal fun appEventPacket(event: Int, batteryPercent: Int): ByteArray =
    byteArrayOf(11, event.toByte(), batteryPercent.coerceIn(0, 100).toByte(), 0)

/** Clears every icon at once; the packet also carries the battery level, like every other. */
internal const val ClearAppEventsEvent = 0

/**
 * The cluster's notification icons, and the phone battery level that rides along with them.
 *
 * Behaves as the OEM app does: an icon is shown whenever a notification for its app is posted
 * and hidden when the last one behind it is dismissed. Unlike the OEM app it tracks every live
 * notification, so one of several being dismissed does not clear the icon, and it keeps
 * tracking while the bike is away so the icons can be relit when the link comes up.
 *
 * The battery level only reaches the cluster inside this packet, so a battery change relights
 * the current icons rather than sending the OEM's bare event 0, which would clear them.
 */
internal class NotificationIcons(
    private val connection: BikeConnection,
    private val settings: StateFlow<AppSettings>,
    private val batteryPercent: () -> Int,
) {
    private val lock = Any()
    private val live = mutableMapOf<String, SupportedNotificationApp>()
    private var lastReplayAtElapsedRealtime = Long.MIN_VALUE

    fun start(context: Context, scope: CoroutineScope) {
        scope.launch {
            connection.connectionState
                .map { it is BikeConnectionState.Connected }
                .distinctUntilChanged()
                .filter { it }
                .collect { replay() }
        }
        scope.launch {
            connection.controls.filter { it is BikeControlEvent.ClusterReady }.collect { replay() }
        }
        scope.launch {
            settings.map { it.disabledNotificationPackages }.distinctUntilChanged().drop(1).collect { replay() }
        }
        context.registerReceiver(
            object : BroadcastReceiver() {
                private var lastPercent = -1

                override fun onReceive(context: Context, intent: Intent) {
                    val percent = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    if (percent == lastPercent) return
                    lastPercent = percent
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastReplayAtElapsedRealtime >= BatteryRefreshIntervalMillis) replay()
                }
            },
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
        )
    }

    fun posted(key: String, app: SupportedNotificationApp) {
        synchronized(lock) { live[key] = app }
        if (app.enabled()) write(app.shownEvent)
    }

    fun removed(key: String) {
        val hidden = synchronized(lock) {
            val app = live.remove(key) ?: return
            app.takeIf { live.values.none { other -> other.shownEvent == app.shownEvent && other.enabled() } }
        } ?: return
        write(hidden.hiddenEvent)
    }

    /** Replaces the tracked set after the listener reconnects, when removals may have been missed. */
    fun reconcile(active: Map<String, SupportedNotificationApp>) {
        synchronized(lock) {
            live.clear()
            live.putAll(active)
        }
        replay()
    }

    /** Clears the cluster's icons and relights the current ones, refreshing the battery level. */
    fun replay() {
        if (connection.connectionState.value !is BikeConnectionState.Connected) return
        lastReplayAtElapsedRealtime = SystemClock.elapsedRealtime()
        val shown = synchronized(lock) { live.values.filter { it.enabled() }.map { it.shownEvent }.distinct() }
        write(ClearAppEventsEvent)
        shown.forEach(::write)
    }

    private fun SupportedNotificationApp.enabled() = packageName !in settings.value.disabledNotificationPackages

    private fun write(event: Int) {
        if (connection.connectionState.value !is BikeConnectionState.Connected) return
        connection.enqueueWrite(BleCharacteristics.AppEvent, appEventPacket(event, batteryPercent()))
    }

    private companion object {
        /** The level changes a percent at a time; once a minute is plenty for a dashboard reading. */
        const val BatteryRefreshIntervalMillis = 60_000L
    }
}
