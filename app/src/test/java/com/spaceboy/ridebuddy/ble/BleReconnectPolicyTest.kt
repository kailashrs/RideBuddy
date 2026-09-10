package com.spaceboy.ridebuddy.ble

import com.spaceboy.ridebuddy.domain.BikeConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BleReconnectPolicyTest {
    @Test
    fun `initial request and its retries share a total three attempt budget`() {
        val budget = ConnectionAttemptBudget()
        assertTrue(budget.beginAttempt())
        assertEquals(1, budget.attemptsStarted)
        assertEquals(1_000L, budget.nextDelayMillis())
        assertTrue(budget.beginAttempt())
        assertEquals(2_000L, budget.nextDelayMillis())
        assertTrue(budget.beginAttempt())
        assertEquals(3, budget.attemptsStarted)
        assertNull(budget.nextDelayMillis())
        assertFalse(budget.beginAttempt())
        assertEquals(3, budget.attemptsStarted)
    }

    @Test
    fun stopsAfterRetryBudgetAndRejectsInvalidCounts() {
        assertNull(reconnectDelayMillis(3))
        assertNull(reconnectDelayMillis(Int.MAX_VALUE))
        assertNull(reconnectDelayMillis(-1))
    }

    @Test
    fun `losing an authenticated connection starts a new three attempt recovery cycle`() {
        val budget = ConnectionAttemptBudget()
        repeat(3) { assertTrue(budget.beginAttempt()) }
        // Production resets only on an explicit request or successful authentication.
        budget.reset()

        assertEquals(1_000L, budget.nextDelayMillis())
        repeat(3) { assertTrue(budget.beginAttempt()) }
        assertNull(budget.nextDelayMillis())
        assertFalse(budget.beginAttempt())
    }

    @Test
    fun launchAutoConnectResumesFromIdleStatesOnly() {
        assertTrue(shouldAutoConnectOnLaunch(BikeConnectionState.Disconnected))
        assertTrue(shouldAutoConnectOnLaunch(BikeConnectionState.Failed("Bluetooth is off")))
        assertFalse(shouldAutoConnectOnLaunch(BikeConnectionState.Connecting("bike")))
        assertFalse(shouldAutoConnectOnLaunch(BikeConnectionState.Authenticating("bike")))
        assertFalse(shouldAutoConnectOnLaunch(BikeConnectionState.Connected("bike", null)))
    }

    @Test
    fun launchAutoConnectDoesNotResetAnExhaustedRetryBudget() {
        assertFalse(
            shouldAutoConnectOnLaunch(
                BikeConnectionState.Failed("out of range", retriesExhausted = true),
            ),
        )
    }
}
