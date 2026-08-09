package com.spaceboy.ridebuddy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationKeyOperationGuardTest {
    @Test
    fun `save test and remove operations cannot overlap`() {
        val guard = NavigationKeyOperationGuard()

        assertTrue(guard.tryAcquire())
        assertFalse(guard.tryAcquire())

        guard.release()
        assertTrue(guard.tryAcquire())
        guard.release()
    }
}
