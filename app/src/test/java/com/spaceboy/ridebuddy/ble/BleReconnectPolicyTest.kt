package com.spaceboy.ridebuddy.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
