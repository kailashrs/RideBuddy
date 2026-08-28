package com.spaceboy.ridebuddy.ble

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PostAuthenticationGateTest {
    private val first = UUID.randomUUID()
    private val second = UUID.randomUUID()

    @Test
    fun `evidence is deferred until every required subscription completes`() {
        val gate = PostAuthenticationGate(listOf(first, second))

        assertNull(gate.acceptEvidence("telemetry"))
        assertFalse(gate.markSubscriptionEnabled(first).becameReady)
        val finalUpdate = gate.markSubscriptionEnabled(second)

        assertTrue(finalUpdate.becameReady)
        assertEquals("telemetry", finalUpdate.deferredEvidence)
        // Readiness is observable through the gate's own answers, not a separate flag.
        assertEquals("VIN", gate.acceptEvidence("VIN"))
    }

    @Test
    fun `only the first early evidence is retained`() {
        val gate = PostAuthenticationGate(listOf(first))

        assertNull(gate.acceptEvidence("VIN"))
        assertNull(gate.acceptEvidence("version"))

        assertEquals("VIN", gate.markSubscriptionEnabled(first).deferredEvidence)
    }

    @Test
    fun `evidence passes through after subscriptions are ready`() {
        val gate = PostAuthenticationGate(listOf(first))
        gate.markSubscriptionEnabled(first)

        assertEquals("valid telemetry", gate.acceptEvidence("valid telemetry"))
    }

    @Test
    fun `duplicate and unrelated subscription callbacks cannot reopen the gate`() {
        val gate = PostAuthenticationGate(listOf(first))
        assertTrue(gate.markSubscriptionEnabled(first).becameReady)

        assertFalse(gate.markSubscriptionEnabled(first).becameReady)
        assertFalse(gate.markSubscriptionEnabled(second).becameReady)
    }
}
