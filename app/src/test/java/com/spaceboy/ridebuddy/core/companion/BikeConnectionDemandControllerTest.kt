package com.spaceboy.ridebuddy.core.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BikeConnectionDemandControllerTest {
    @Test
    fun `duplicate BLE appearance cannot reset the reconnect budget`() {
        val transition = bikeConnectionDemandTransition(
            BikeConnectionDemandState(blePresence = ObservedBlePresence.Present),
            BikeConnectionDemandEvent.BleAppeared,
        )

        assertEquals(BleAppearanceDecision.IgnoreDuplicate, transition.appearanceDecision)
    }

    @Test
    fun `manual disconnect suppresses queued and duplicate appearances`() {
        val disconnected = bikeConnectionDemandTransition(
            BikeConnectionDemandState(blePresence = ObservedBlePresence.Present),
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
        assertEquals(ObservedBlePresence.Unknown, explicit.state.blePresence)
    }
}
