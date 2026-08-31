package com.spaceboy.ridebuddy.ble

import com.spaceboy.ridebuddy.domain.BikeConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BleReconnectPolicyTest {
    @Test
    fun usesBoundedExponentialBackoff() {
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L), (0..5).map(::reconnectDelayMillis))
    }

    @Test
    fun stopsAfterRetryBudgetAndRejectsInvalidCounts() {
        assertNull(reconnectDelayMillis(6))
        assertNull(reconnectDelayMillis(-1))
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
