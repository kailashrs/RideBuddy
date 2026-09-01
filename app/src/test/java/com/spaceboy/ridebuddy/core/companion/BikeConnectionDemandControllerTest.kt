package com.spaceboy.ridebuddy.core.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BikeConnectionDemandControllerTest {
    /**
     * Every appearance can ask for a connection unless the rider disconnected on purpose.
     *
     * Presence callbacks are edge-triggered, so an appearance is already a rare, meaningful event.
     * Tracking whether the bike was "already present" and discarding repeats only added a way to
     * throw away the one signal that resumes a link which has stopped retrying — leaving the app
     * stranded beside a bike that is still advertising. Refusing a second concurrent attempt is
     * the caller's job, and it already does it from the connection state.
     */
    @Test
    fun `repeated appearances can each request a connection`() {
        val first = bikeConnectionDemandTransition(
            BikeConnectionDemandState(),
            BikeConnectionDemandEvent.BleAppeared,
        )
        assertEquals(BleAppearanceDecision.RequestConnection, first.appearanceDecision)

        val second = bikeConnectionDemandTransition(first.state, BikeConnectionDemandEvent.BleAppeared)

        assertEquals(BleAppearanceDecision.RequestConnection, second.appearanceDecision)
    }

    @Test
    fun `manual disconnect suppresses later appearances`() {
        val disconnected = bikeConnectionDemandTransition(
            BikeConnectionDemandState(),
            BikeConnectionDemandEvent.ManualDisconnect,
        ).state

        val queuedAppearance = bikeConnectionDemandTransition(
            disconnected,
            BikeConnectionDemandEvent.BleAppeared,
        )

        assertEquals(BleAppearanceDecision.IgnoreWhileSuppressed, queuedAppearance.appearanceDecision)
        assertEquals(
            AutomaticConnectionDemand.SuppressedUntilBleDisappears,
            queuedAppearance.state.automaticConnectionDemand,
        )
    }

    @Test
    fun `suppression survives an unknown presence until the bike disappears`() {
        val disconnected = bikeConnectionDemandTransition(
            BikeConnectionDemandState(),
            BikeConnectionDemandEvent.ManualDisconnect,
        ).state
        val appeared = bikeConnectionDemandTransition(disconnected, BikeConnectionDemandEvent.BleAppeared)

        assertEquals(BleAppearanceDecision.IgnoreWhileSuppressed, appeared.appearanceDecision)

        val disappeared = bikeConnectionDemandTransition(
            appeared.state,
            BikeConnectionDemandEvent.BleDisappeared,
        )
        val returned = bikeConnectionDemandTransition(
            disappeared.state,
            BikeConnectionDemandEvent.BleAppeared,
        )

        assertEquals(BleAppearanceDecision.RequestConnection, returned.appearanceDecision)
        assertEquals(AutomaticConnectionDemand.Allowed, returned.state.automaticConnectionDemand)
    }

    @Test
    fun `explicit connect immediately overrides manual suppression`() {
        val disconnected = bikeConnectionDemandTransition(
            BikeConnectionDemandState(),
            BikeConnectionDemandEvent.ManualDisconnect,
        ).state
        val explicit = bikeConnectionDemandTransition(
            disconnected,
            BikeConnectionDemandEvent.ExplicitConnect,
        )

        assertEquals(AutomaticConnectionDemand.Allowed, explicit.state.automaticConnectionDemand)
        assertNull(explicit.appearanceDecision)
    }
}
