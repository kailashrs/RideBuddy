package com.spaceboy.ridebuddy.core.tft

import com.google.android.libraries.mapsplatform.turnbyturn.model.Maneuver
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
    fun `the call surface walks every state and ends with the call cleared`() = runBlocking {
        val now = 10_000L
        val connection = RecordingConnection(receivedAtElapsedRealtime = now)
        val validator = StationaryTftValidator(
            connection,
            pauseBetweenWrites = {},
            elapsedRealtimeMillis = { now },
        )

        val result = validator.run(StationaryTftPhase.Calls)

        assertEquals(StationaryTftTestResult.Succeeded(7), result)
        assertEquals(
            listOf(
                BleCharacteristics.CallerName,
                BleCharacteristics.CallerNumber,
                BleCharacteristics.CallState,
                BleCharacteristics.CallState,
                BleCharacteristics.CallState,
                BleCharacteristics.CallState,
                BleCharacteristics.CallState,
            ),
            connection.writes.map { it.characteristic },
        )
        val states = connection.writes
            .filter { it.characteristic == BleCharacteristics.CallState }
            .map { it.payload.toList() }
        assertEquals(
            listOf(
                TftCallEncoder.ringing().toList(),
                TftCallEncoder.accepted().toList(),
                TftCallEncoder.ended().toList(),
                TftCallEncoder.outgoing().toList(),
                // Ends cleared, so no invented call is left showing on the cluster.
                TftCallEncoder.ended().toList(),
            ),
            states,
        )
    }

    @Test
    fun `the call test sends a number long enough to prove the cluster truncation`() = runBlocking {
        val now = 10_000L
        val connection = RecordingConnection(receivedAtElapsedRealtime = now)
        val validator = StationaryTftValidator(
            connection,
            pauseBetweenWrites = {},
            elapsedRealtimeMillis = { now },
        )

        validator.run(StationaryTftPhase.Calls)

        val number = connection.writes.first { it.characteristic == BleCharacteristics.CallerNumber }
        assertEquals("9876543210", number.payload.copyOfRange(0, 10).toString(Charsets.US_ASCII))
    }

    @Test
    fun `writes every inferred navigation output and ends by clearing the TFT`() = runBlocking {
        val now = 10_000L
        val connection = RecordingConnection(receivedAtElapsedRealtime = now)
        val validator = StationaryTftValidator(
            connection,
            pauseBetweenWrites = {},
            elapsedRealtimeMillis = { now },
        )

        val result = validator.run(StationaryTftPhase.Navigation, nowMillis = 1_700_000_000_000L)

        assertEquals(StationaryTftTestResult.Succeeded(11), result)
        assertEquals(
            listOf(
                BleCharacteristics.NavigationSession,
                BleCharacteristics.NavigationStatus,
                BleCharacteristics.NavigationSession,
                BleCharacteristics.NavigationManeuver,
                BleCharacteristics.NavigationTrip,
                BleCharacteristics.NavigationText,
                BleCharacteristics.NavigationText,
                BleCharacteristics.NavigationText,
                BleCharacteristics.NavigationSpeedLimit,
                BleCharacteristics.NavigationClear,
                BleCharacteristics.NavigationSpeedLimit,
            ),
            connection.writes.map { it.characteristic },
        )
        assertEquals(
            listOf(
                BikeWriteMode.NoResponsePreferred,
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
    fun `the session sequence is the one a route started without a preview transmits`() = runBlocking {
        val now = 10_000L
        val connection = RecordingConnection(receivedAtElapsedRealtime = now)
        val validator = StationaryTftValidator(
            connection,
            pauseBetweenWrites = {},
            elapsedRealtimeMillis = { now },
        )

        validator.run(StationaryTftPhase.Navigation)

        // The route-request value first, then guidance — the transition production makes when
        // the worker drains between the request and the first guidance update. No preview 83,
        // because a direct start never passes through it; and no status 0, which is a sentinel
        // the bridge suppresses rather than sends.
        assertEquals(
            listOf(TftPacketEncoder.session(80).toList(), TftPacketEncoder.session(87).toList()),
            connection.writes
                .filter { it.characteristic == BleCharacteristics.NavigationSession }
                .map { it.payload.toList() },
        )
        assertEquals(
            listOf(TftPacketEncoder.status(132).toList()),
            connection.writes
                .filter { it.characteristic == BleCharacteristics.NavigationStatus }
                .map { it.payload.toList() },
        )
    }

    @Test
    fun `the maneuver phase draws real turn arrows rather than the fallback pictogram`() = runBlocking {
        val now = 10_000L
        val connection = RecordingConnection(receivedAtElapsedRealtime = now)
        val validator = StationaryTftValidator(
            connection,
            pauseBetweenWrites = {},
            elapsedRealtimeMillis = { now },
        )

        validator.run(StationaryTftPhase.Navigation)

        val maneuver = connection.writes.first { it.characteristic == BleCharacteristics.NavigationManeuver }
        // Right now, left next: opposite directions, so a swapped or dropped arrow is visible
        // on the cluster rather than being indistinguishable from the correct output.
        assertEquals(TftPacketEncoder.clusterManeuver(Maneuver.TURN_RIGHT), maneuver.payload[1].toInt() and 0xFF)
        assertEquals(TftPacketEncoder.clusterManeuver(Maneuver.TURN_LEFT), maneuver.payload[4].toInt() and 0xFF)
    }

    @Test
    fun `teardown clears the display and zeroes the speed limit it set`() = runBlocking {
        val now = 10_000L
        val connection = RecordingConnection(receivedAtElapsedRealtime = now)
        val validator = StationaryTftValidator(
            connection,
            pauseBetweenWrites = {},
            elapsedRealtimeMillis = { now },
        )

        validator.run(StationaryTftPhase.Navigation)

        // The clear packet does not touch the speed limit, so the phase would otherwise leave
        // its own 60 showing on a cluster that is no longer in a navigation session.
        val tail = connection.writes.takeLast(2)
        assertEquals(BleCharacteristics.NavigationClear, tail[0].characteristic)
        assertEquals(TftPacketEncoder.clear().toList(), tail[0].payload.toList())
        assertEquals(BleCharacteristics.NavigationSpeedLimit, tail[1].characteristic)
        assertEquals(TftPacketEncoder.speedLimit(0).toList(), tail[1].payload.toList())
    }

    @Test
    fun `stops at the first unacknowledged write`() = runBlocking {
        val now = 10_000L
        // Index 4 is the trip packet: session, status, session, maneuver, then trip.
        val connection = RecordingConnection(failAtWrite = 5, receivedAtElapsedRealtime = now)
        val validator = StationaryTftValidator(
            connection,
            pauseBetweenWrites = {},
            elapsedRealtimeMillis = { now },
        )

        val result = validator.run(StationaryTftPhase.Navigation)

        assertEquals(StationaryTftTestResult.Failed(BleCharacteristics.NavigationTrip, 4), result)
        assertEquals(5, connection.writes.size)
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

        val result = validator.run(StationaryTftPhase.Navigation)

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

        val result = validator.run(StationaryTftPhase.Navigation)

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

        val result = validator.run(StationaryTftPhase.Navigation)

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
    override fun enqueueWrite(characteristic: UUID, payload: ByteArray) = Unit

    fun publishSpeed(speedKph: Double, receivedAtElapsedRealtime: Long) {
        mutableLatestTelemetryReading.value = reading(speedKph, receivedAtElapsedRealtime)
    }

    override suspend fun writeAndAwait(write: BikeWrite): Boolean {
        writes += Write(write.characteristic, write.payload, write.mode)
        return failAtWrite != writes.size
    }

    private companion object {
        fun reading(speedKph: Double, receivedAtElapsedRealtime: Long) = TelemetryReading(
            frame = TelemetryFrame(
                speedKilometresPerHour = speedKph,
                throttlePercent = 0,
                instantaneousMileageKilometresPerLitre = null,
                engineRpm = 0,
            ),
            receivedAtMillis = 1_700_000_000_000L,
            receivedAtElapsedRealtime = receivedAtElapsedRealtime,
        )
    }
}
