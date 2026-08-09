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

    @Test
    fun navigationReplayRequiresActiveGuidanceEnabledOutputAndCachedInfo() {
        assertTrue(shouldReplayTftNavigation(true, true, true))
        assertFalse(shouldReplayTftNavigation(false, true, true))
        assertFalse(shouldReplayTftNavigation(true, false, true))
        assertFalse(shouldReplayTftNavigation(true, true, false))
    }

    @Test
    fun disablingOutputResetsAnySurfaceThatCouldRemainOnTheCluster() {
        assertTrue(shouldResetTftOutput(sessionActive = true, textAlertActive = false, hasLastInfo = false))
        assertTrue(shouldResetTftOutput(sessionActive = false, textAlertActive = true, hasLastInfo = false))
        assertTrue(shouldResetTftOutput(sessionActive = false, textAlertActive = false, hasLastInfo = true))
        assertFalse(shouldResetTftOutput(sessionActive = false, textAlertActive = false, hasLastInfo = false))
    }
}
