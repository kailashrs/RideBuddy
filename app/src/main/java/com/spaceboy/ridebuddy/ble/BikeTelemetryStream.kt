package com.spaceboy.ridebuddy.ble

import com.spaceboy.ridebuddy.domain.TelemetryReading
import java.util.ArrayDeque
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

internal data class TelemetryAcceptance(
    val valid: Boolean,
    val telemetryHz: Double,
)

/** Publishes full-rate ride data and a separately sampled, mileage-smoothed UI stream. */
@OptIn(FlowPreview::class)
internal class BikeTelemetryStream(
    scope: CoroutineScope,
) {
    private val timestamps = ArrayDeque<Long>()
    private val mileageSmoother = LiveMileageSmoother()
    private val rawChannel = Channel<TelemetryReading>(Channel.UNLIMITED)
    private val mutableRawTelemetry = MutableSharedFlow<TelemetryReading>(
        extraBufferCapacity = RawTelemetryBufferCapacity,
    )
    private val mutableTelemetry = MutableStateFlow<TelemetryFrame?>(null)
    private val mutableLatestReading = MutableStateFlow<TelemetryReading?>(null)

    val rawTelemetry: SharedFlow<TelemetryReading> = mutableRawTelemetry
    val telemetry: StateFlow<TelemetryFrame?> = mutableTelemetry
        .sample(TelemetrySampleIntervalMillis.milliseconds)
        .stateIn(scope, SharingStarted.Eagerly, null)
    val latestReading: StateFlow<TelemetryReading?> = mutableLatestReading.asStateFlow()

    init {
        scope.launch {
            for (reading in rawChannel) mutableRawTelemetry.emit(reading)
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
            ?: return TelemetryAcceptance(valid = false, telemetryHz = telemetryHz)
        val reading = TelemetryReading(
            frame = frame,
            receivedAtMillis = receivedAtMillis,
            // Match the previous connection path: freshness time is captured only after parsing
            // succeeds, so malformed frames never advance the monotonic telemetry clock.
            receivedAtElapsedRealtime = elapsedRealtime(),
        )
        mutableLatestReading.value = reading
        mutableTelemetry.value = mileageSmoother.smooth(frame)
        rawChannel.trySend(reading)
        return TelemetryAcceptance(valid = true, telemetryHz = telemetryHz)
    }

    fun reset() {
        timestamps.clear()
        mileageSmoother.reset()
        mutableTelemetry.value = null
        mutableLatestReading.value = null
    }

    fun clearUiTelemetry() {
        mutableTelemetry.value = null
    }

    private companion object {
        const val TelemetryWindowMillis = 5_000L
        const val TelemetrySampleIntervalMillis = 100L
        const val RawTelemetryBufferCapacity = 256
    }
}
