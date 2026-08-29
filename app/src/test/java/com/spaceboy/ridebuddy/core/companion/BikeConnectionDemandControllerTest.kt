package com.spaceboy.ridebuddy.core.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BikeConnectionDemandControllerTest {
    /**
     * The regression this guards is in the presence service, but the mechanism is here. That
     * service consumed the appearance before checking whether it could act on it, and while an
     * attempt was already in flight it then threw the appearance away. The bike is Present from
     * that point, so every later appearance is a duplicate — and if the in-flight attempt fails
     * its way to `retriesExhausted`, the app reaches the one state a fresh appearance exists to
     * resume with no appearance left to resume it, stranded beside a bike that is still
     * advertising.
     */
    @Test
    fun `an appearance spent while an attempt is in flight leaves nothing to resume with`() {
        val spent = bikeConnectionDemandTransition(
            BikeConnectionDemandState(blePresence = ObservedBlePresence.Absent),
            BikeConnectionDemandEvent.BleAppeared,
        )
        assertEquals(BleAppearanceDecision.RequestConnection, spent.appearanceDecision)

        // The bike never went away, so nothing here can ask for a connection again.
        val later = bikeConnectionDemandTransition(spent.state, BikeConnectionDemandEvent.BleAppeared)

        assertEquals(BleAppearanceDecision.IgnoreDuplicate, later.appearanceDecision)
    }

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
