package com.spaceboy.ridebuddy.core.tft

import com.spaceboy.ridebuddy.ble.BikeConnectionTarget
import com.spaceboy.ridebuddy.ble.BleCharacteristics
import com.spaceboy.ridebuddy.ble.TelemetryFrame
import com.spaceboy.ridebuddy.data.AppSettings
import com.spaceboy.ridebuddy.domain.BikeConnection
import com.spaceboy.ridebuddy.domain.BikeConnectionState
import com.spaceboy.ridebuddy.domain.BikeControlEvent
import com.spaceboy.ridebuddy.domain.BikeIdentity
import com.spaceboy.ridebuddy.domain.BikeWrite
import com.spaceboy.ridebuddy.domain.BleDiagnostics
import com.spaceboy.ridebuddy.domain.TelemetryReading
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the stateful half of the bridge: what actually reaches the cluster, and that a cluster
 * which never acknowledges a write cannot hold the write worker in a retry loop.
 *
 * The bridge paces its own writes, so these tests wait in real time. Every wait is well clear of
 * the interval it is covering: [SettleMillis] exceeds a fully paced batch, and [GiveUpMillis]
 * exceeds the whole 1s + 2s failure backoff.
 */
class TftNavigationBridgeTest {
    @Test
    fun `nothing is written while TFT output is disabled`() = withBridge(outputEnabled = false) { bridge, connection ->
        connection.authenticate()
        settle()
        bridge.start()

        assertFalse(bridge.presentTextAlert("HELLO"))
        bridge.stop()
        settle()

        assertEquals(emptyList<UUID>(), connection.writtenCharacteristics())
    }

    @Test
    fun `nothing is written before the link is authenticated`() = withBridge { bridge, connection ->
        bridge.start()

        assertFalse(bridge.presentTextAlert("HELLO"))
        settle()

        assertEquals(emptyList<UUID>(), connection.writtenCharacteristics())
    }

    @Test
    fun `an alert opens a session and writes the text rows`() = withBridge { bridge, connection ->
        connection.authenticate()
        settle()
        bridge.start()

        assertTrue(bridge.presentTextAlert("HELLO"))
        settle()

        val written = connection.writtenCharacteristics()
        assertTrue("expected a session frame, got $written", BleCharacteristics.NavigationSession in written)
        assertTrue("expected text rows, got $written", BleCharacteristics.NavigationText in written)
    }

    @Test
    fun `stopping an active session clears the cluster`() = withBridge { bridge, connection ->
        connection.authenticate()
        settle()
        bridge.start()
        bridge.presentTextAlert("HELLO")
        settle()
        connection.clearWrites()

        bridge.stop()
        settle()

        val written = connection.writtenCharacteristics()
        assertTrue("expected a clear frame, got $written", BleCharacteristics.NavigationClear in written)
    }

    @Test
    fun `stopping a requested route before its first update clears the cluster`() =
        withBridge { bridge, connection ->
            connection.authenticate()
            settle()
            bridge.start("HOME")
            settle()
            connection.clearWrites()

            bridge.stop()
            settle()

            val written = connection.writtenCharacteristics()
            assertTrue(
                "expected the pending route to be cleared, got $written",
                BleCharacteristics.NavigationClear in written,
            )
            assertFalse("a stale session followed the clear", BleCharacteristics.NavigationSession in written)
            assertFalse("a stale status followed the clear", BleCharacteristics.NavigationStatus in written)
        }

    @Test
    fun `a staged destination is restored after reconnect`() = withBridge { bridge, connection ->
        connection.authenticate()
        settle()
        bridge.previewDestination("HOME")
        settle()
        connection.clearWrites()

        connection.disconnect()
        settle()
        connection.authenticate()
        settle()

        val sessions = connection.payloadsFor(BleCharacteristics.NavigationSession)
        assertEquals(83, sessions.single()[2].toInt() and 0xFF)
        assertTrue(
            "expected destination text after reconnect",
            BleCharacteristics.NavigationText in connection.writtenCharacteristics(),
        )
    }

    @Test
    fun `rerouting keeps an active alert on the text rows`() = withBridge { bridge, connection ->
        connection.authenticate()
        settle()
        bridge.start("HOME")
        assertTrue(bridge.presentTextAlert("ROUTE ALERT"))
        settle()
        connection.clearWrites()

        bridge.rerouting()
        settle()

        val written = connection.writtenCharacteristics()
        assertTrue(
            "expected the recalculation pictogram, got $written",
            BleCharacteristics.NavigationManeuver in written,
        )
        assertFalse("rerouting overwrote the active alert", BleCharacteristics.NavigationText in written)
    }

    @Test
    fun `a superseded session coalesces but its status word still goes out once`() =
        withBridge { bridge, connection ->
            // The transport is down, so both marks sit as state and coalesce before anything
            // drains: staging a destination marks the preview session, and starting a route then
            // replaces it while marking the status word for that request.
            bridge.previewDestination("HOME")
            bridge.start("HOME")

            connection.authenticate()
            settle()

            val sessions = connection.payloadsFor(BleCharacteristics.NavigationSession)
            val statuses = connection.payloadsFor(BleCharacteristics.NavigationStatus)
            assertEquals("only the newest session value should reach the wire", 1, sessions.size)
            assertEquals(80, sessions.single()[2].toInt() and 0xFF)
            assertEquals("status survives session coalescing, once per request", 1, statuses.size)
            assertEquals(132, statuses.single()[1].toInt() and 0xFF)
        }

    @Test
    fun `teardown writes the clear without any stale session or status`() =
        withBridge { bridge, connection ->
            connection.authenticate()
            settle()
            bridge.start()
            bridge.presentTextAlert("HELLO")
            settle()
            connection.clearWrites()

            bridge.stop()
            settle()

            val written = connection.writtenCharacteristics()
            assertTrue("expected a clear frame, got $written", BleCharacteristics.NavigationClear in written)
            assertFalse("a stale session followed the clear", BleCharacteristics.NavigationSession in written)
            assertFalse("a stale status followed the clear", BleCharacteristics.NavigationStatus in written)
        }

    @Test
    fun `a cluster that never acknowledges writes stops being retried`() = withBridge { bridge, connection ->
        connection.authenticate()
        settle()
        connection.acceptWrites = false
        bridge.start()
        bridge.presentTextAlert("HELLO")

        delay(GiveUpMillis)
        val attemptsAfterGivingUp = connection.writeCount()
        delay(GiveUpMillis)

        assertEquals(
            "the worker kept retrying a cluster that never acknowledges a write",
            attemptsAfterGivingUp,
            connection.writeCount(),
        )
    }

    private suspend fun settle() = delay(SettleMillis)

    private fun withBridge(
        outputEnabled: Boolean = true,
        block: suspend (TftNavigationBridge, FakeBikeConnection) -> Unit,
    ) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val connection = FakeBikeConnection()
        try {
            val bridge = TftNavigationBridge(
                connection = connection,
                settings = MutableStateFlow(AppSettings(tftNavigationOutputEnabled = outputEnabled)),
                scope = scope,
            )
            runBlocking { block(bridge, connection) }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a failed preview batch is retried instead of being routed into the coalescing map`() =
        withBridge { bridge, connection ->
            connection.authenticate()
            settle()
            connection.clearWrites()

            // Preview runs with no session active, so anything restored into the coalescing map is
            // stamped with a session generation it can never satisfy and is dropped silently.
            connection.failOnce = BleCharacteristics.NavigationText
            bridge.previewDestination("HOME")
            delay(GiveUpMillis)

            val rows = connection.payloadsFor(BleCharacteristics.NavigationText)
            assertTrue("the failed preview batch was never retried", rows.size > 1)
            assertEquals(
                "the retry did not carry all three rows",
                listOf(0, 1, 2),
                rows.takeLast(3).map { it[1].toInt() and 0xFF },
            )
        }

    private companion object {
        const val SettleMillis = 1_500L
        const val GiveUpMillis = 5_000L
    }
}

private class FakeBikeConnection : BikeConnection {
    private val writes = CopyOnWriteArrayList<BikeWrite>()

    @Volatile
    var acceptWrites = true

    /** Fails the next write to this characteristic only, then clears itself. */
    @Volatile
    var failOnce: UUID? = null

    private val mutableConnectionState =
        MutableStateFlow<BikeConnectionState>(BikeConnectionState.Disconnected)
    private val mutableDiagnostics = MutableStateFlow(BleDiagnostics())

    override val connectionState: StateFlow<BikeConnectionState> = mutableConnectionState
    override val rawTelemetry: SharedFlow<TelemetryReading> = MutableSharedFlow()
    override val telemetry: StateFlow<TelemetryFrame?> = MutableStateFlow(null)
    override val latestTelemetryReading: StateFlow<TelemetryReading?> = MutableStateFlow(null)
    override val identity: StateFlow<BikeIdentity> = MutableStateFlow(BikeIdentity())
    override val diagnostics: StateFlow<BleDiagnostics> = mutableDiagnostics
    override val controls: SharedFlow<BikeControlEvent> = MutableSharedFlow()

    fun authenticate() {
        mutableConnectionState.value = BikeConnectionState.Connected("Test bike", null)
        mutableDiagnostics.value = BleDiagnostics(authenticated = true)
    }

    fun writtenCharacteristics(): List<UUID> = writes.map { it.characteristic }.distinct()

    fun writeCount(): Int = writes.size

    fun payloadsFor(characteristic: UUID): List<ByteArray> =
        writes.filter { it.characteristic == characteristic }.map { it.payload }

    fun clearWrites() = writes.clear()

    override fun connect(target: BikeConnectionTarget) = Unit

    override fun disconnect() {
        mutableConnectionState.value = BikeConnectionState.Disconnected
        mutableDiagnostics.value = BleDiagnostics()
    }

    override fun enqueueWrite(characteristic: UUID, payload: ByteArray) = Unit

    override suspend fun writeAndAwait(write: BikeWrite): Boolean {
        writes += write
        if (failOnce == write.characteristic) {
            failOnce = null
            return false
        }
        return acceptWrites
    }
}
