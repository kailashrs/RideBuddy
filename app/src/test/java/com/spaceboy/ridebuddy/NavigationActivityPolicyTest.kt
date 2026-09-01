package com.spaceboy.ridebuddy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationActivityPolicyTest {
    @Test
    fun `full map attaches to running guidance`() {
        assertEquals(
            NavigationLaunchPolicy.AttachExisting,
            navigationLaunchPolicy(
                attachRequested = true,
                guidanceWasStarted = false,
                guidanceIsRunning = true,
            ),
        )
    }

    @Test
    fun `new destination replaces unrelated running guidance`() {
        assertEquals(
            NavigationLaunchPolicy.PrepareNewRoute,
            navigationLaunchPolicy(
                attachRequested = false,
                guidanceWasStarted = false,
                guidanceIsRunning = true,
            ),
        )
    }

    @Test
    fun `full map reports missing active guidance instead of calculating a route`() {
        assertEquals(
            NavigationLaunchPolicy.NoActiveRoute,
            navigationLaunchPolicy(
                attachRequested = true,
                guidanceWasStarted = false,
                guidanceIsRunning = false,
            ),
        )
    }

    @Test
    fun `background lifecycle keeps only started or running guidance`() {
        assertFalse(shouldKeepGuidanceInBackground(guidanceStarted = false, guidanceIsRunning = false))
        assertTrue(shouldKeepGuidanceInBackground(guidanceStarted = true, guidanceIsRunning = false))
        assertTrue(shouldKeepGuidanceInBackground(guidanceStarted = false, guidanceIsRunning = true))
        assertTrue(shouldKeepGuidanceInBackground(guidanceStarted = true, guidanceIsRunning = true))
    }

    @Test
    fun `stale activity cannot release the current navigation session`() {
        val ownership = NavigationSessionOwnership()
        ownership.register(1L)
        assertTrue(ownership.claim(1L))
        ownership.register(2L)
        assertTrue(ownership.claim(2L))

        assertFalse(ownership.release(1L))
        assertTrue(ownership.isOwner(2L))
        assertTrue(ownership.release(2L))
        assertFalse(ownership.isOwner(2L))
    }

    @Test
    fun `older ready callback cannot claim while newer activity is pending`() {
        val ownership = NavigationSessionOwnership()
        ownership.register(1L)
        ownership.register(2L)

        assertFalse(ownership.claim(1L))
        assertFalse(ownership.claim(1L))
        assertTrue(ownership.claim(2L))
        assertTrue(ownership.isOwner(2L))
    }
}
