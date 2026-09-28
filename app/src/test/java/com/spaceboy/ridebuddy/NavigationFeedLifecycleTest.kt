package com.spaceboy.ridebuddy

import com.google.android.libraries.mapsplatform.turnbyturn.model.NavInfo
import com.google.android.libraries.mapsplatform.turnbyturn.model.NavState
import com.spaceboy.ridebuddy.core.navigation.NavigationFeedRepository
import org.junit.Assert.*
import org.junit.Test

class NavigationFeedLifecycleTest {
    @Test fun onlyStartedGuidancePublishesAndItContinuesWithTheMapClosed() {
        val world = World()
        world.publishActiveUpdates()
        assertFalse(world.feed.guidance.value.active)
        assertEquals(0, world.forwarded)

        world.lifecycle.registerPendingSession(1)
        world.lifecycle.attach(1, world.session) {}
        world.publishActiveUpdates()
        assertFalse(world.feed.guidance.value.active)
        assertEquals(0, world.forwarded)

        world.lifecycle.markGuidanceStarted(1)
        world.publishActiveUpdates()
        assertTrue(world.feed.guidance.value.active)
        assertEquals(2, world.forwarded)
        world.lifecycle.detachUi(1)
        world.publishActiveUpdates()
        assertEquals(4, world.forwarded)
    }

    @Test fun endedRouteRejectsQueuedMessagesButANewRouteCanStart() {
        val world = World()
        world.start(1)
        world.publishActiveUpdates()
        assertTrue(world.lifecycle.release(world.session.identity))
        world.feed.clear()

        world.publishActiveUpdates()
        assertFalse(world.feed.guidance.value.active)
        assertEquals(2, world.forwarded)
        world.start(2)
        world.publishActiveUpdates()
        assertTrue(world.feed.guidance.value.active)
        assertEquals(4, world.forwarded)
    }

    @Test fun arrivalAndTerminalUpdatesRejectSubsequentActiveMessages() {
        val arrival = World()
        arrival.start(1)
        arrival.session.onArrival?.invoke(true)
        arrival.publishActiveUpdates()
        assertFalse(arrival.feed.guidance.value.active)
        assertEquals(0, arrival.forwarded)

        val stopped = World()
        stopped.start(1)
        stopped.session.running = false
        stopped.feed.accept(info(NavState.STOPPED))
        stopped.publishActiveUpdates()
        assertFalse(stopped.feed.guidance.value.active)
        assertEquals(1, stopped.forwarded)
    }

    @Test fun replacingRouteRejectsOldUpdatesWhileNewSessionIsPending() {
        val world = World()
        world.start(1)
        world.lifecycle.registerPendingSession(2)
        world.publishActiveUpdates()
        assertEquals(0, world.forwarded)
        world.lifecycle.abandonPendingSession(2)
        world.publishActiveUpdates()
        assertEquals(2, world.forwarded)
    }

    private class World {
        val feed = NavigationFeedRepository()
        val lifecycle = NavigationGuidanceLifecycle(feed::clear, {})
        val session = Session()
        var forwarded = 0
        init {
            feed.acceptActiveNavInfo = lifecycle::acceptsActiveFeed
            feed.acceptTerminalNavInfo = lifecycle::acceptAndMarkTerminalFeed
            feed.onNavInfo = { forwarded++ }
        }
        fun start(id: Long) {
            lifecycle.registerPendingSession(id)
            lifecycle.attach(id, session) {}
            lifecycle.markGuidanceStarted(id)
        }
        fun publishActiveUpdates() {
            feed.accept(info(NavState.ENROUTE))
            feed.accept(info(NavState.REROUTING))
        }
    }

    private class Session : NavigationGuidanceSession {
        override val identity = Any()
        var running = true
        override val isGuidanceRunning get() = running
        var onArrival: ((Boolean) -> Unit)? = null
        override fun setArrivalHandler(handler: ((Boolean) -> Unit)?) { onArrival = handler }
        override fun stopGuidance() { running = false }
        override fun unregisterServiceForNavUpdates() {}
        override fun cleanup() {}
        override fun continueToNextDestination() {}
    }

    companion object {
        private fun info(state: Int) = NavInfo.builder().setNavState(state)
            .setRemainingSteps(emptyArray()).setRouteChanged(false).build()
    }
}
