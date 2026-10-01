package com.spaceboy.ridebuddy.ble

import com.spaceboy.ridebuddy.domain.TelemetryReading
import java.util.ArrayDeque
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Publishes every decoded frame for recording, and the newest one for everything else.
 *
 * The cluster sends telemetry at about 4 Hz and [RideRecorder][com.spaceboy.ridebuddy.data.RideRecorder]
 * is its only consumer, so [rawBufferCapacity] is several seconds of slack. Frames are handed over
 * with `tryEmit`, which never suspends the GATT callback thread.
 *
 * All state here is confined to the connection's main handler, which is the only caller of [accept]
 * and [reset].
 */
internal class BikeTelemetryStream(
    rawBufferCapacity: Int = RawTelemetryBufferCapacity,
) {
    private val timestamps = ArrayDeque<Long>()
    private val mutableRawTelemetry = MutableSharedFlow<TelemetryReading>(
        extraBufferCapacity = rawBufferCapacity,
    )
    private val mutableLatestReading = MutableStateFlow<TelemetryReading?>(null)

    val readings: SharedFlow<TelemetryReading> = mutableRawTelemetry
    val latestReading: StateFlow<TelemetryReading?> = mutableLatestReading.asStateFlow()

    /**
     * Parses one telemetry payload, publishes it to both streams, and returns the measured rate.
     * [elapsedRealtime] drives the rate window and freshness.
     */
    fun accept(
        payload: ByteArray,
        receivedAtMillis: Long,
        elapsedRealtime: () -> Long,
    ): Double {
        // Rolling window of arrival times, trimmed to the last few seconds; its size is the rate.
        val monotonicNow = elapsedRealtime()
        timestamps.addLast(monotonicNow)
        while (timestamps.firstOrNull()?.let { monotonicNow - it > TelemetryWindowMillis } == true) {
            timestamps.removeFirst()
        }
        val telemetryHz = timestamps.size / (TelemetryWindowMillis / 1_000.0)
        val frame = parseTelemetryFrame(payload) ?: return telemetryHz
        val reading = TelemetryReading(
            frame = frame,
            receivedAtMillis = receivedAtMillis,
            receivedAtElapsedRealtime = monotonicNow,
        )
        mutableLatestReading.value = reading
        mutableRawTelemetry.tryEmit(reading)
        return telemetryHz
    }

    /** Full teardown between sessions: rate window and latest reading. */
    fun reset() {
        timestamps.clear()
        mutableLatestReading.value = null
    }

    /**
     * Blanks the displayed values while leaving the rate history intact — used when a new
     * attempt starts, so the UI stops showing a stale speed.
     */
    fun clearUiTelemetry() {
        mutableLatestReading.value = null
    }

    private companion object {
        /** Averaging window for the reported rate. */
        const val TelemetryWindowMillis = 5_000L

        /** About four seconds of slack at the cluster's ~4 Hz notification rate. */
        const val RawTelemetryBufferCapacity = 16
    }
}
