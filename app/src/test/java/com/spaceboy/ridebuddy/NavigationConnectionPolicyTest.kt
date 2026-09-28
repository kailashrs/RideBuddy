package com.spaceboy.ridebuddy

import com.spaceboy.ridebuddy.domain.BikeConnectionState
import org.junit.Assert.*
import org.junit.Test

class NavigationConnectionPolicyTest {
    private val connected = BikeConnectionState.Connected("RS 457", null)

    @Test fun disconnectedPreviewSurvivesAnEarlierFailureAndRequiresGoAfterConnecting() {
        val policy = NavigationConnectionPolicy(autoStartPending = true)
        assertFalse(policy.onConnectionState(BikeConnectionState.Failed("Previous attempt")))
        assertFalse(policy.onConnectionState(BikeConnectionState.Disconnected))
        assertFalse(policy.onConnectionState(BikeConnectionState.Connecting("RS 457")))
        assertFalse(policy.onConnectionState(connected))
        assertFalse(policy.consumeAutoStart(connected))
    }

    @Test fun connectedSharedRouteStartsOnlyOnce() {
        val policy = NavigationConnectionPolicy(autoStartPending = true)
        assertFalse(policy.onConnectionState(connected))
        assertTrue(policy.consumeAutoStart(connected))
        assertFalse(policy.consumeAutoStart(connected))
    }

    @Test fun reconnectDuringRouteCalculationDoesNotRearmAutoStart() {
        val policy = NavigationConnectionPolicy(autoStartPending = true)
        policy.onConnectionState(connected)
        assertFalse(policy.onConnectionState(BikeConnectionState.Connecting("RS 457", 2, 3)))
        assertFalse(policy.onConnectionState(BikeConnectionState.Authenticating("RS 457")))
        policy.onConnectionState(connected)
        assertFalse(policy.consumeAutoStart(connected))
    }

    @Test fun routeResultChecksCurrentConnectionEvenBeforeStateCollectorRuns() {
        val policy = NavigationConnectionPolicy(autoStartPending = true)
        assertFalse(policy.consumeAutoStart(BikeConnectionState.Disconnected))
        assertFalse(policy.consumeAutoStart(connected))
    }

    @Test fun newConnectionFailureEndsSessionButTemporaryRetriesDoNot() {
        val policy = NavigationConnectionPolicy(autoStartPending = false)
        policy.onConnectionState(connected)
        for (attempt in 1..3) {
            assertFalse(policy.onConnectionState(BikeConnectionState.Connecting("RS 457", attempt, 3)))
        }
        assertTrue(policy.onConnectionState(BikeConnectionState.Failed("Connection failed", retriesExhausted = true)))
    }

    @Test fun rotationPreservesConsumedAutoStartAndConnectionSession() {
        val policy = NavigationConnectionPolicy(autoStartPending = true)
        policy.onConnectionState(connected)
        policy.consumeAutoStart(connected)
        val restored = NavigationConnectionPolicy(policy.autoStartPending, policy.connectionSessionSeen)
        assertFalse(restored.consumeAutoStart(connected))
        assertTrue(restored.onConnectionState(BikeConnectionState.Disconnected))
    }

    @Test fun everyNonReadyStateBlocksGuidanceAndUpdateRegistration() {
        val states = listOf(
            BikeConnectionState.Disconnected,
            BikeConnectionState.Connecting("RS 457"),
            BikeConnectionState.Authenticating("RS 457"),
            BikeConnectionState.Failed("Connection failed"),
        )
        states.forEach { state ->
            assertEquals(GuidanceStartResult.ConnectionRequired, startConnectedGuidance(
                connectionState = { state },
                registerUpdates = { error("Must not register") },
                unregisterUpdates = { error("Nothing registered") },
                startGuidance = { error("Must not start") },
            ))
        }
    }

    @Test fun connectionLostWhileRegisteringUpdatesUnregistersWithoutStarting() {
        var state: BikeConnectionState = connected
        var unregisters = 0
        val result = startConnectedGuidance(
            connectionState = { state },
            registerUpdates = { state = BikeConnectionState.Connecting("RS 457", 1, 3); true },
            unregisterUpdates = { unregisters++ },
            startGuidance = { error("Must not start after link loss") },
        )
        assertEquals(GuidanceStartResult.ConnectionRequired, result)
        assertEquals(1, unregisters)
    }

    @Test fun readyConnectionStartsAfterRegisteringAndRejectsRegistrationFailure() {
        val events = mutableListOf<String>()
        assertEquals(GuidanceStartResult.Started, startConnectedGuidance(
            connectionState = { connected },
            registerUpdates = { events += "register"; true },
            unregisterUpdates = { events += "unregister" },
            startGuidance = { events += "start" },
        ))
        assertEquals(listOf("register", "start"), events)
        assertEquals(GuidanceStartResult.UpdatesUnavailable, startConnectedGuidance(
            connectionState = { connected },
            registerUpdates = { false },
            unregisterUpdates = { error("Nothing registered") },
            startGuidance = { error("No bike updates") },
        ))
    }
}
