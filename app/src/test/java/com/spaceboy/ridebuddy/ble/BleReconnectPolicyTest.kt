package com.spaceboy.ridebuddy.ble

import com.spaceboy.ridebuddy.domain.BikeConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BleReconnectPolicyTest {
    @Test
    fun `an attempt cycle is three attempts, 1 s then 2 s apart`() {
        assertEquals(1_000L, reconnectDelayMillis(0))
        assertEquals(1_000L, reconnectDelayMillis(1))
        assertEquals(2_000L, reconnectDelayMillis(2))
        assertNull(reconnectDelayMillis(3))
    }

    @Test
    fun stopsAfterRetryBudgetAndRejectsInvalidCounts() {
        assertNull(reconnectDelayMillis(3))
        assertNull(reconnectDelayMillis(Int.MAX_VALUE))
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
