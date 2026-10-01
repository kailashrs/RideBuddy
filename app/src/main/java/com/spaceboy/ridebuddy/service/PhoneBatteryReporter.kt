package com.spaceboy.ridebuddy.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.SystemClock
import com.spaceboy.ridebuddy.ble.BleCharacteristics
import com.spaceboy.ridebuddy.domain.BikeConnection
import com.spaceboy.ridebuddy.domain.BikeConnectionState
import com.spaceboy.ridebuddy.domain.BikeControlEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The OEM app-event packet on `8110`, `[0x0B, event, phone battery percent, 0x00]`, with no
 * event. The cluster draws the battery byte but ignores the event byte, so the icons the OEM
 * app sends through the same packet are never shown.
 */
internal fun phoneBatteryPacket(batteryPercent: Int): ByteArray =
    byteArrayOf(11, 0, batteryPercent.coerceIn(0, 100).toByte(), 0)

/**
 * Keeps the phone battery level on the cluster current: sent when the link comes up, when
 * the cluster announces it has (re)started, and when the level changes.
 */
internal class PhoneBatteryReporter(
    private val connection: BikeConnection,
    private val batteryPercent: () -> Int,
) {
    @Volatile private var lastSentAtElapsedRealtime = Long.MIN_VALUE

    fun start(context: Context, scope: CoroutineScope) {
        scope.launch {
            connection.connectionState
                .map { it is BikeConnectionState.Connected }
                .distinctUntilChanged()
                .filter { it }
                .collect { send() }
        }
        scope.launch {
            connection.controls.filter { it is BikeControlEvent.ClusterReady }.collect { send() }
        }
        context.registerReceiver(
            object : BroadcastReceiver() {
                private var lastPercent = -1

                override fun onReceive(context: Context, intent: Intent) {
                    val percent = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    if (percent == lastPercent) return
                    lastPercent = percent
                    if (SystemClock.elapsedRealtime() - lastSentAtElapsedRealtime >= RefreshIntervalMillis) send()
                }
            },
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
        )
    }

    fun send() {
        if (connection.connectionState.value !is BikeConnectionState.Connected) return
        lastSentAtElapsedRealtime = SystemClock.elapsedRealtime()
        connection.enqueueWrite(BleCharacteristics.AppEvent, phoneBatteryPacket(batteryPercent()))
    }

    private companion object {
        /** The level changes a percent at a time; once a minute is plenty for a dashboard reading. */
        const val RefreshIntervalMillis = 60_000L
    }
}
