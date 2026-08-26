package com.spaceboy.ridebuddy.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BikeConnectionServiceTest {
    @Test
    fun `location runs only for a ride or navigation under a location foreground service`() {
        assertFalse(shouldTrackRideLocation(false, hasActiveRide = false, hasActiveNavigation = false))
        assertFalse(shouldTrackRideLocation(true, hasActiveRide = false, hasActiveNavigation = false))
        assertFalse(shouldTrackRideLocation(false, hasActiveRide = true, hasActiveNavigation = false))
        assertFalse(shouldTrackRideLocation(false, hasActiveRide = false, hasActiveNavigation = true))
        assertTrue(shouldTrackRideLocation(true, hasActiveRide = true, hasActiveNavigation = false))
        assertTrue(shouldTrackRideLocation(true, hasActiveRide = false, hasActiveNavigation = true))
    }
}
