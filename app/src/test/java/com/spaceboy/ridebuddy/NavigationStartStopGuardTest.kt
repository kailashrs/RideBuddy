package com.spaceboy.ridebuddy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationStartStopGuardTest {
    @Test
    fun duplicateStopIsRejectedUntilTheCurrentRequestFinishes() {
        val guard = NavigationStartStopGuard()
        val request = guard.beginStop()

        assertNotNull(request)
        assertNull(guard.beginStop())
        assertTrue(guard.isCurrentStop(request!!))

        guard.finishStop(request)
        assertFalse(guard.isCurrentStop(request))
        assertNotNull(guard.beginStop())
    }

    @Test
    fun startingANewRouteInvalidatesALateStopCallback() {
        val retainedGuard = NavigationStartStopGuard()
        val firstActivityGuard = retainedGuard
        val recreatedActivityGuard = retainedGuard
        val stopRequest = firstActivityGuard.beginStop()!!

        recreatedActivityGuard.beginStart()

        assertFalse(firstActivityGuard.isCurrentStop(stopRequest))
        assertNotNull(recreatedActivityGuard.beginStop())
    }
}
