package com.spaceboy.ridebuddy.core.tft

import com.spaceboy.ridebuddy.ble.BleCharacteristics
import com.spaceboy.ridebuddy.ble.TelemetryFrame
import com.spaceboy.ridebuddy.ble.BikeConnectionTarget
import com.spaceboy.ridebuddy.domain.BikeConnection
import com.spaceboy.ridebuddy.domain.BikeConnectionState
import com.spaceboy.ridebuddy.domain.BikeControlEvent
import com.spaceboy.ridebuddy.domain.BikeIdentity
import com.spaceboy.ridebuddy.domain.BikeWrite
import com.spaceboy.ridebuddy.domain.BikeWriteMode
import com.spaceboy.ridebuddy.domain.BleDiagnostics
import com.spaceboy.ridebuddy.domain.TelemetryReading
import java.util.UUID
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StationaryTftValidatorTest {
    @Test
    fun `writes every inferred navigation output and ends by clearing the TFT`() = runBlocking {
        val now = 10_000L
        val connection = RecordingConnection(receivedAtElapsedRealtime = now)
        val validator = StationaryTftValidator(
            connection,
            pauseBetweenWrites = {},
            elapsedRealtimeMillis = { now },
        )

        val result = validator.run(nowMillis = 1_700_000_000_000L)

        assertEquals(StationaryTftTestResult.Succeeded(11), result)
        assertEquals(
            listOf(
                BleCharacteristics.NavigationSession,
                BleCharacteristics.NavigationStatus,
                BleCharacteristics.NavigationManeuver,
                BleCharacteristics.NavigationTrip,
                BleCharacteristics.NavigationText,
                BleCharacteristics.NavigationText,
                BleCharacteristics.NavigationText,
                BleCharacteristics.NavigationSpeedLimit,
                BleCharacteristics.NavigationSession,
                BleCharacteristics.NavigationClear,
                BleCharacteristics.NavigationStatus,
            ),
            connection.writes.map { it.characteristic },
        )
        assertEquals(
            listOf(
                BikeWriteMode.NoResponsePreferred,
                BikeWriteMode.NoResponsePreferred,
                BikeWriteMode.NoResponsePreferred,
            ),
            connection.writes.filter { write ->
                write.characteristic in setOf(
                    BleCharacteristics.NavigationManeuver,
                    BleCharacteristics.NavigationTrip,
                    BleCharacteristics.NavigationSpeedLimit,
                )
            }.map { it.mode },
        )
    }

    @Test
    fun `stops at the first unacknowledged write`() = runBlocking {
        val now = 10_000L
        val connection = RecordingConnection(failAtWrite = 4, receivedAtElapsedRealtime = now)
        val validator = StationaryTftValidator(
            connection,
            pauseBetweenWrites = {},
            elapsedRealtimeMillis = { now },
        )

        val result = validator.run()

        assertEquals(StationaryTftTestResult.Failed(BleCharacteristics.NavigationTrip, 3), result)
        assertEquals(4, connection.writes.size)
        assertTrue(connection.writes.none { it.characteristic == BleCharacteristics.NavigationClear })
    }

    @Test
    fun `refuses to start when the latest telemetry is stale`() = runBlocking {
        val now = 10_000L
        val connection = RecordingConnection(receivedAtElapsedRealtime = now - 2_001L)
        val validator = StationaryTftValidator(
            connection,
            pauseBetweenWrites = {},
            elapsedRealtimeMillis = { now },
        )

        val result = validator.run()

        assertEquals(
            StationaryTftTestResult.SafetyStopped(StationaryTftSafetyReason.TelemetryStale, 0),
            result,
        )
        assertTrue(connection.writes.isEmpty())
    }

    @Test
    fun `rechecks movement before every write`() = runBlocking {
        val now = 10_000L
        val connection = RecordingConnection(receivedAtElapsedRealtime = now)
        val validator = StationaryTftValidator(
            connection,
            pauseBetweenWrites = { connection.publishSpeed(8.0, now) },
            elapsedRealtimeMillis = { now },
        )

        val result = validator.run()

        assertEquals(
            StationaryTftTestResult.SafetyStopped(StationaryTftSafetyReason.BikeMoving, 1),
            result,
        )
        assertEquals(1, connection.writes.size)
    }

    @Test
    fun `rechecks telemetry freshness before every write`() = runBlocking {
        var now = 10_000L
        val connection = RecordingConnection(receivedAtElapsedRealtime = now)
        val validator = StationaryTftValidator(
            connection,
            pauseBetweenWrites = { now += 2_001L },
            elapsedRealtimeMillis = { now },
        )

        val result = validator.run()

        assertEquals(
            StationaryTftTestResult.SafetyStopped(StationaryTftSafetyReason.TelemetryStale, 1),
            result,
        )
        assertEquals(1, connection.writes.size)
    }
}

private class RecordingConnection(
    private val failAtWrite: Int? = null,
    receivedAtElapsedRealtime: Long,
) : BikeConnection {
    data class Write(val characteristic: UUID, val payload: ByteArray, val mode: BikeWriteMode)

    val writes = mutableListOf<Write>()
    override val connectionState: StateFlow<BikeConnectionState> =
        MutableStateFlow(BikeConnectionState.Connected("RS 457", null))
    override val rawTelemetry: SharedFlow<TelemetryReading> = MutableSharedFlow()
    override val telemetry: StateFlow<TelemetryFrame?> = MutableStateFlow(null)
    private val mutableLatestTelemetryReading = MutableStateFlow<TelemetryReading?>(
        reading(speedKph = 0.0, receivedAtElapsedRealtime = receivedAtElapsedRealtime),
    )
    override val latestTelemetryReading: StateFlow<TelemetryReading?> = mutableLatestTelemetryReading
    override val identity: StateFlow<BikeIdentity> = MutableStateFlow(BikeIdentity())
    override val diagnostics: StateFlow<BleDiagnostics> = MutableStateFlow(BleDiagnostics(authenticated = true))
    override val controls: SharedFlow<BikeControlEvent> = MutableSharedFlow()

    override fun connect(target: BikeConnectionTarget) = Unit
    override fun disconnect() = Unit
    override fun write(characteristic: UUID, payload: ByteArray) = true

    fun publishSpeed(speedKph: Double, receivedAtElapsedRealtime: Long) {
        mutableLatestTelemetryReading.value = reading(speedKph, receivedAtElapsedRealtime)
    }

    override suspend fun writeAndAwait(characteristic: UUID, payload: ByteArray): Boolean =
        writeAndAwait(BikeWrite(characteristic, payload))

    override suspend fun writeAndAwait(write: BikeWrite): Boolean {
        writes += Write(write.characteristic, write.payload, write.mode)
        return failAtWrite != writes.size
    }

    private companion object {
        fun reading(speedKph: Double, receivedAtElapsedRealtime: Long) = TelemetryReading(
            frame = TelemetryFrame(
                speedKilometresPerHour = speedKph,
                throttlePercent = 0,
                instantaneousConsumptionLitresPer100Km = 0.0,
                engineRpm = 0,
            ),
            receivedAtMillis = 1_700_000_000_000L,
            receivedAtElapsedRealtime = receivedAtElapsedRealtime,
        )
    }
}
