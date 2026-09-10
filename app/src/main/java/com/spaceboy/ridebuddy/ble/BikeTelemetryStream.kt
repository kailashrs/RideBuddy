package com.spaceboy.ridebuddy.ble

import com.spaceboy.ridebuddy.domain.TelemetryReading
import java.util.ArrayDeque
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Outcome of one telemetry notification.
 *
 * [valid] is false when the payload did not parse as a telemetry frame. The two counters
 * are diagnostics: [telemetryHz] is the rate measured over a rolling window, and
 * [droppedRawTelemetryFrames] is cumulative for the session.
 */
internal data class TelemetryAcceptance(
    val valid: Boolean,
    val telemetryHz: Double,
    val droppedRawTelemetryFrames: Long,
)

/**
 * Publishes full-rate ride data and a separately mileage-smoothed UI stream.
 *
 * The cluster sends telemetry at about 4 Hz and [RideRecorder][com.spaceboy.ridebuddy.data.RideRecorder]
 * is its only consumer, so [rawBufferCapacity] is several seconds of slack. Frames are handed over
 * with `tryEmit`, which never suspends the GATT callback thread: a frame that cannot be buffered is
 * dropped and counted rather than stalling the link. Reaching that counter means the consumer has
 * stopped keeping up with a 4 Hz stream, which is why it is surfaced in diagnostics.
 *
 * All state here is confined to the connection's main handler, which is the only caller of [accept]
 * and [reset].
 */
internal class BikeTelemetryStream(
    rawBufferCapacity: Int = RawTelemetryBufferCapacity,
) {
    private val timestamps = ArrayDeque<Long>()
    private val mileageSmoother = LiveMileageSmoother()
    private var droppedRawTelemetryFrames = 0L
    private val mutableRawTelemetry = MutableSharedFlow<TelemetryReading>(
        extraBufferCapacity = rawBufferCapacity,
    )
    private val mutableTelemetry = MutableStateFlow<TelemetryFrame?>(null)
    private val mutableLatestReading = MutableStateFlow<TelemetryReading?>(null)

    val rawTelemetry: SharedFlow<TelemetryReading> = mutableRawTelemetry
    val telemetry: StateFlow<TelemetryFrame?> = mutableTelemetry.asStateFlow()
    val latestReading: StateFlow<TelemetryReading?> = mutableLatestReading.asStateFlow()

    /**
     * Parses one telemetry payload and publishes it to both streams.
     *
     * [elapsedRealtime] drives both the rate window and freshness, so changing the phone's
     * date or time cannot distort the rate or retain timestamps indefinitely.
     */
    fun accept(
        payload: ByteArray,
        receivedAtMillis: Long,
        elapsedRealtime: () -> Long,
    ): TelemetryAcceptance {
        // Rolling window of arrival times, trimmed to the last few seconds; its size is
        // the measured rate. Recorded before parsing so malformed frames still count as
        // link activity — the rate answers "is the bike talking", not "is it talking sense".
        val monotonicNow = elapsedRealtime()
        timestamps.addLast(monotonicNow)
        while (timestamps.firstOrNull()?.let { monotonicNow - it > TelemetryWindowMillis } == true) {
            timestamps.removeFirst()
        }
        val telemetryHz = timestamps.size / (TelemetryWindowMillis / 1_000.0)
        val frame = TelemetryFrame.parse(payload)
            ?: return TelemetryAcceptance(
                valid = false,
                telemetryHz = telemetryHz,
                droppedRawTelemetryFrames = droppedRawTelemetryFrames,
            )
        val reading = TelemetryReading(
            frame = frame,
            receivedAtMillis = receivedAtMillis,
            receivedAtElapsedRealtime = monotonicNow,
        )
        mutableLatestReading.value = reading
        mutableTelemetry.value = mileageSmoother.smooth(frame)
        if (!mutableRawTelemetry.tryEmit(reading)) droppedRawTelemetryFrames++
        return TelemetryAcceptance(
            valid = true,
            telemetryHz = telemetryHz,
            droppedRawTelemetryFrames = droppedRawTelemetryFrames,
        )
    }

    /** Full teardown between sessions: rate window, filter state, counters and both streams. */
    fun reset() {
        timestamps.clear()
        mileageSmoother.reset()
        droppedRawTelemetryFrames = 0L
        mutableTelemetry.value = null
        mutableLatestReading.value = null
    }

    /**
     * Blanks the displayed values while leaving the session's counters and filter state
     * intact — used when the link is momentarily quiet, so the UI stops showing a stale
     * speed without the rate history being thrown away.
     */
    fun clearUiTelemetry() {
        mutableTelemetry.value = null
    }

    private companion object {
        /** Averaging window for the reported rate. */
        const val TelemetryWindowMillis = 5_000L

        /** About four seconds of slack at the cluster's ~4 Hz notification rate. */
        const val RawTelemetryBufferCapacity = 16
    }
}
