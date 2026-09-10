package com.spaceboy.ridebuddy.service

import com.spaceboy.ridebuddy.domain.BikeConnectionState
import com.spaceboy.ridebuddy.domain.ConnectionAttemptTrigger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BikeConnectionServiceTest {
    @Test
    fun `foreground stays until ride save completes and Android accepts the stop`() = runBlocking {
        val saved = CompletableDeferred<Unit>()
        val actions = mutableListOf<String>()
        val shutdown = launch(start = CoroutineStart.UNDISPATCHED) {
            stopConnectionServiceAfterSave(
                stopId = 7,
                saveRide = { saved.await(); actions += "saved"; true },
                stopIfCurrent = { id -> actions += "stop:$id"; true },
                removeForeground = { actions += "foreground removed" },
            )
        }
        assertTrue(actions.isEmpty())

        saved.complete(Unit)
        shutdown.join()

        assertEquals(listOf("saved", "stop:7", "foreground removed"), actions)
    }

    @Test
    fun `new start known to Android keeps foreground even before onStartCommand can cancel old shutdown`() = runBlocking {
        val saved = CompletableDeferred<Unit>()
        var newestStartId = 7
        var foregroundRemoved = false
        var attemptedStopId: Int? = null
        val shutdown = launch(start = CoroutineStart.UNDISPATCHED) {
            stopConnectionServiceAfterSave(
                stopId = 7,
                saveRide = { saved.await(); true },
                stopIfCurrent = { id -> attemptedStopId = id; id == newestStartId },
                removeForeground = { foregroundRemoved = true },
            )
        }
        newestStartId = 8
        saved.complete(Unit)
        shutdown.join()

        assertEquals(7, attemptedStopId)
        assertFalse(foregroundRemoved)
    }

    @Test
    fun `cancelled shutdown cannot stop the newer session or remove its foreground`() = runBlocking {
        val saved = CompletableDeferred<Unit>()
        var stopRequested = false
        var foregroundRemoved = false
        val shutdown = launch(start = CoroutineStart.UNDISPATCHED) {
            stopConnectionServiceAfterSave(
                stopId = 7,
                saveRide = { saved.await(); true },
                stopIfCurrent = { stopRequested = true; true },
                removeForeground = { foregroundRemoved = true },
            )
        }
        shutdown.cancelAndJoin()
        saved.complete(Unit)

        assertFalse(stopRequested)
        assertFalse(foregroundRemoved)
    }

    @Test
    fun `save failure retains foreground protection and never asks Android to stop`() = runBlocking {
        var stopRequested = false
        var foregroundRemoved = false
        val saved = stopConnectionServiceAfterSave(
            stopId = 7,
            saveRide = { false },
            stopIfCurrent = { stopRequested = true; true },
            removeForeground = { foregroundRemoved = true },
        )

        assertFalse(saved)
        assertFalse(stopRequested)
        assertFalse(foregroundRemoved)
    }

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
