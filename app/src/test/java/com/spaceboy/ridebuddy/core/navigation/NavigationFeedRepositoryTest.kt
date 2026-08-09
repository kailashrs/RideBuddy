package com.spaceboy.ridebuddy.core.navigation

import com.google.android.libraries.mapsplatform.turnbyturn.model.NavInfo
import com.google.android.libraries.mapsplatform.turnbyturn.model.NavState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationFeedRepositoryTest {
    @Test
    fun reroutingKeepsKnownManeuverFieldsAndRemainsActive() {
        val previous = GuidanceState(
            active = true,
            instruction = "Turn left",
            roadName = "Market Road",
            distanceToManeuverMetres = 250,
            distanceToDestinationMetres = 4_000,
            timeToDestinationSeconds = 600,
            maneuver = 7,
            nextManeuver = 3,
        )

        val rerouting = previous.asRerouting(
            distanceToDestinationMetres = null,
            timeToDestinationSeconds = 550,
        )

        assertTrue(rerouting.active)
        assertEquals("Rerouting…", rerouting.instruction)
        assertEquals("Market Road", rerouting.roadName)
        assertEquals(250, rerouting.distanceToManeuverMetres)
        assertEquals(4_000, rerouting.distanceToDestinationMetres)
        assertEquals(550, rerouting.timeToDestinationSeconds)
        assertEquals(7, rerouting.maneuver)
        assertEquals(3, rerouting.nextManeuver)
    }

    @Test
    fun reroutingNavInfoIsForwardedToTheRegisteredConsumer() {
        val repository = NavigationFeedRepository()
        val info = NavInfo.builder()
            .setNavState(NavState.REROUTING)
            .setRemainingSteps(emptyArray())
            .setRouteChanged(false)
            .build()
        var forwarded: NavInfo? = null
        repository.onNavInfo = { forwarded = it }

        repository.accept(info)

        assertTrue(repository.guidance.value.active)
        assertSame(info, forwarded)
        assertEquals(NavigationFeedOutputAction.Rerouting, navigationFeedOutputAction(info.navState))
    }

    @Test
    fun stoppedNavInfoClearsGuidanceAndIsForwardedToStopTftOutput() {
        val repository = NavigationFeedRepository()
        val enroute = NavInfo.builder()
            .setNavState(NavState.ENROUTE)
            .setRemainingSteps(emptyArray())
            .setRouteChanged(false)
            .build()
        val stopped = NavInfo.builder()
            .setNavState(NavState.STOPPED)
            .setRemainingSteps(emptyArray())
            .setRouteChanged(false)
            .build()
        var forwarded: NavInfo? = null
        var terminalDecisions = 0
        repository.onNavInfo = { forwarded = it }
        repository.acceptTerminalNavInfo = {
            terminalDecisions++
            true
        }

        repository.accept(enroute)
        repository.accept(stopped)

        assertFalse(repository.guidance.value.active)
        assertSame(stopped, forwarded)
        assertEquals(1, terminalDecisions)
        assertEquals(NavigationFeedOutputAction.Stop, navigationFeedOutputAction(stopped.navState))
    }

    @Test
    fun staleStoppedNavInfoCannotClearNewerGuidance() {
        val repository = NavigationFeedRepository()
        val enroute = NavInfo.builder()
            .setNavState(NavState.ENROUTE)
            .setRemainingSteps(emptyArray())
            .setRouteChanged(false)
            .build()
        val stopped = NavInfo.builder()
            .setNavState(NavState.STOPPED)
            .setRemainingSteps(emptyArray())
            .setRouteChanged(false)
            .build()
        var forwarded: NavInfo? = null
        repository.onNavInfo = { forwarded = it }
        repository.acceptTerminalNavInfo = { false }

        repository.accept(enroute)
        forwarded = null
        repository.accept(stopped)

        assertTrue(repository.guidance.value.active)
        assertEquals("Guidance active", repository.guidance.value.instruction)
        assertNull(forwarded)
    }
}
