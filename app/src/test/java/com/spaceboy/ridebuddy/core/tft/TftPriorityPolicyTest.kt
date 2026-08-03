package com.spaceboy.ridebuddy.core.tft

import org.junit.Test
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class TftPriorityPolicyTest {
    @Test
    fun callAlwaysSuppressesNotification() {
        assertFalse(tftNotificationAllowed(true, false, null))
    }

    @Test
    fun imminentTurnSuppressesButDistantTurnAllows() {
        assertFalse(tftNotificationAllowed(false, true, 250))
        assertTrue(tftNotificationAllowed(false, true, 750))
        assertTrue(tftTurnIsImminent(true, 500))
        assertFalse(tftTurnIsImminent(false, 100))
    }

    @Test
    fun notificationAllowedWithoutHigherPrioritySurface() {
        assertTrue(tftNotificationAllowed(false, false, null))
    }
}
