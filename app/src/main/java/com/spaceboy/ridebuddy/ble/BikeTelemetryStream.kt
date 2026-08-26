package com.spaceboy.ridebuddy.ble

import com.spaceboy.ridebuddy.domain.TelemetryReading
import java.util.ArrayDeque
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class TelemetryAcceptance(
    val valid: Boolean,
    val telemetryHz: Double,
    val droppedRawTelemetryFrames: Long,
)

/** Publishes full-rate ride data and a separately mileage-smoothed UI stream. */
internal class BikeTelemetryStream(
    scope: CoroutineScope,
) {
    private val timestamps = ArrayDeque<Long>()
    private val mileageSmoother = LiveMileageSmoother()
    private val rawQueue = BoundedTelemetryQueue(RawTelemetryBufferCapacity)
    private val rawQueueWakeups = Channel<Unit>(Channel.CONFLATED)
    private val mutableRawTelemetry = MutableSharedFlow<TelemetryReading>()
    private val mutableTelemetry = MutableStateFlow<TelemetryFrame?>(null)
    private val mutableLatestReading = MutableStateFlow<TelemetryReading?>(null)

    val rawTelemetry: SharedFlow<TelemetryReading> = mutableRawTelemetry
    val telemetry: StateFlow<TelemetryFrame?> = mutableTelemetry.asStateFlow()
    val latestReading: StateFlow<TelemetryReading?> = mutableLatestReading.asStateFlow()

    init {
        scope.launch {
            for (ignored in rawQueueWakeups) {
                while (true) {
                    val reading = rawQueue.poll() ?: break
                    mutableRawTelemetry.emit(reading)
                }
            }
        }
    }

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
                droppedRawTelemetryFrames = rawQueue.droppedCount,
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
        val droppedRawTelemetryFrames = rawQueue.offer(reading)
        rawQueueWakeups.trySend(Unit)
        return TelemetryAcceptance(
            valid = true,
            telemetryHz = telemetryHz,
            droppedRawTelemetryFrames = droppedRawTelemetryFrames,
        )
    }

    fun reset() {
        timestamps.clear()
        mileageSmoother.reset()
        rawQueue.reset()
        mutableTelemetry.value = null
        mutableLatestReading.value = null
    }

    fun clearUiTelemetry() {
        mutableTelemetry.value = null
    }

    private companion object {
        const val TelemetryWindowMillis = 5_000L
        const val RawTelemetryBufferCapacity = 256
    }
}

internal class BoundedTelemetryQueue(private val capacity: Int) {
    private val readings = ArrayDeque<TelemetryReading>(capacity)
    private var mutableDroppedCount = 0L

    init {
        require(capacity > 0) { "Telemetry queue capacity must be positive" }
    }

    val droppedCount: Long
        get() = synchronized(readings) { mutableDroppedCount }

    fun offer(reading: TelemetryReading): Long = synchronized(readings) {
        if (readings.size == capacity) {
            readings.removeFirst()
            mutableDroppedCount++
        }
        readings.addLast(reading)
        mutableDroppedCount
    }

    fun poll(): TelemetryReading? = synchronized(readings) { readings.pollFirst() }

    fun reset() = synchronized(readings) {
        readings.clear()
        mutableDroppedCount = 0L
    }
}
