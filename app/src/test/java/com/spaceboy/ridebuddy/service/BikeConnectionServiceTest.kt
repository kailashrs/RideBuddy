package com.spaceboy.ridebuddy.service

import com.spaceboy.ridebuddy.domain.BikeConnectionState
import com.spaceboy.ridebuddy.domain.ConnectionAttemptTrigger
import org.junit.Assert.assertEquals
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

    @Test
    fun `terminal states remove the foreground service instead of publishing a stale action`() {
        assertEquals(
            ConnectionServiceStateAction.WaitForStartCommand,
            connectionServiceStateAction(BikeConnectionState.Disconnected, receivedStartCommand = false),
        )
        assertEquals(
            ConnectionServiceStateAction.StopService,
            connectionServiceStateAction(BikeConnectionState.Disconnected, receivedStartCommand = true),
        )
        assertEquals(
            ConnectionServiceStateAction.StopService,
            connectionServiceStateAction(BikeConnectionState.Failed("failed"), receivedStartCommand = true),
        )
        assertEquals(
            ConnectionServiceStateAction.PublishNotification,
            connectionServiceStateAction(BikeConnectionState.Authenticating("bike"), receivedStartCommand = true),
        )
    }

    @Test
    fun `every trigger except an explicit request is suppressed by a manual disconnect`() {
        assertFalse(ConnectionAttemptTrigger.UserRequest.isAutomatic())
        assertTrue(ConnectionAttemptTrigger.PresenceAppearance.isAutomatic())
        assertTrue(ConnectionAttemptTrigger.AppLaunch.isAutomatic())
    }

    @Test
    fun `service destruction does not turn a failure into disconnected`() {
        assertFalse(connectionRequiresGattShutdown(BikeConnectionState.Disconnected))
        assertFalse(connectionRequiresGattShutdown(BikeConnectionState.Failed("failed")))
        assertTrue(connectionRequiresGattShutdown(BikeConnectionState.Connecting("bike")))
        assertTrue(connectionRequiresGattShutdown(BikeConnectionState.Connected("bike", null)))
    }
}
