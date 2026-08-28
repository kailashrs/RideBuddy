package com.spaceboy.ridebuddy.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GattSessionTest {
    /** Stands in for a BluetoothGatt instance and counts how often it was retired. */
    private class FakeTransport(val name: String) {
        var closeCount = 0
        var disconnectedFirst = false
    }

    private val closures = mutableListOf<Pair<FakeTransport, Boolean>>()

    private fun closeTransport(transport: FakeTransport, disconnectFirst: Boolean) {
        transport.closeCount++
        if (disconnectFirst) transport.disconnectedFirst = true
        closures += transport to disconnectFirst
    }

    private fun registry() = GattSessionRegistry(::closeTransport)

    private fun session(transport: FakeTransport) =
        GattSession(id = 1, transport = transport, openedAtElapsedRealtime = 0L, closeTransport = ::closeTransport)

    @Test
    fun `a session closes its transport exactly once`() {
        val transport = FakeTransport("first")
        val session = session(transport)

        assertTrue(session.close(disconnectFirst = false))
        assertFalse(session.close(disconnectFirst = false))
        assertFalse(session.close(disconnectFirst = true))

        assertEquals(1, transport.closeCount)
        assertNull(session.openTransport())
    }

    @Test
    fun `link age is reported only after the transport connected`() {
        val session = session(FakeTransport("first"))

        assertNull(session.linkAgeMillis(5_000L))

        session.markConnected(1_000L)
        session.markConnected(4_000L)

        assertEquals(1_000L, session.connectedAtElapsedRealtime)
        assertEquals(4_000L, session.linkAgeMillis(5_000L))
        assertEquals(0L, session.linkAgeMillis(0L))
    }

    @Test
    fun `a retired transport is recognised instead of being closed a second time`() {
        val registry = registry()
        val transport = FakeTransport("first")

        val session = registry.open(transport, openedAtElapsedRealtime = 0L)
        assertTrue(registry.isCurrent(transport))
        assertFalse(registry.isRetired(transport))

        registry.retireCurrent(disconnectFirst = true)

        assertNull(session.openTransport())
        assertTrue(transport.disconnectedFirst)
        assertFalse(registry.isCurrent(transport))
        assertTrue(registry.isRetired(transport))
        assertNull(registry.current())

        // The stale-callback path asks the registry rather than closing blindly.
        assertFalse(session.close(disconnectFirst = false))
        assertEquals(1, transport.closeCount)
    }

    @Test
    fun `retiring twice closes nothing the second time`() {
        val registry = registry()
        val transport = FakeTransport("first")
        registry.open(transport, openedAtElapsedRealtime = 0L)

        registry.retireCurrent(disconnectFirst = false)
        assertNull(registry.retireCurrent(disconnectFirst = false))

        assertEquals(1, transport.closeCount)
    }

    @Test
    fun `opening a new session retires the previous one exactly once`() {
        val registry = registry()
        val firstTransport = FakeTransport("first")
        val secondTransport = FakeTransport("second")

        val first = registry.open(firstTransport, openedAtElapsedRealtime = 0L)
        val second = registry.open(secondTransport, openedAtElapsedRealtime = 10L)

        assertNull(first.openTransport())
        assertSame(secondTransport, second.openTransport())
        assertNotEquals(first.id, second.id)
        assertSame(second, registry.current())
        assertEquals(1, firstTransport.closeCount)
        assertEquals(0, secondTransport.closeCount)
    }

    @Test
    fun `an unadopted transport is closed once and remembered as retired`() {
        val registry = registry()
        val transport = FakeTransport("superseded")

        registry.closeUnadopted(transport, openedAtElapsedRealtime = 0L)

        assertEquals(1, transport.closeCount)
        assertTrue(registry.isRetired(transport))
        assertFalse(registry.isCurrent(transport))
        assertNull(registry.current())
    }

    @Test
    fun `every retired session stays recognisable, however much the link churned`() {
        val registry = registry()
        val transports = List(12) { index -> FakeTransport("t$index") }

        transports.forEach { transport -> registry.open(transport, openedAtElapsedRealtime = 0L) }
        registry.retireCurrent(disconnectFirst = false)

        // A very late callback from the oldest session must still be recognised rather than
        // closed a second time, so the history cannot be capped at a handful of entries.
        transports.forEach { transport ->
            assertEquals(1, transport.closeCount)
            assertTrue(registry.isRetired(transport))
            assertFalse(registry.isCurrent(transport))
        }
        assertEquals(12, closures.size)
        assertFalse(registry.isRetired(FakeTransport("never opened")))
    }
}
