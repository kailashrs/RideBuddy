package com.spaceboy.ridebuddy.ble

import com.spaceboy.ridebuddy.domain.TelemetryReading
import java.util.ArrayDeque
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

    fun accept(
        payload: ByteArray,
        receivedAtMillis: Long,
        elapsedRealtime: () -> Long,
    ): TelemetryAcceptance {
        timestamps.addLast(receivedAtMillis)
        while (timestamps.firstOrNull()?.let { receivedAtMillis - it > TelemetryWindowMillis } == true) {
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
            // Match the previous connection path: freshness time is captured only after parsing
            // succeeds, so malformed frames never advance the monotonic telemetry clock.
            receivedAtElapsedRealtime = elapsedRealtime(),
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

    fun reset() {
        timestamps.clear()
        mileageSmoother.reset()
        droppedRawTelemetryFrames = 0L
        mutableTelemetry.value = null
        mutableLatestReading.value = null
    }

    fun clearUiTelemetry() {
        mutableTelemetry.value = null
    }

    private companion object {
        const val TelemetryWindowMillis = 5_000L
        const val RawTelemetryBufferCapacity = 16
    }
}
