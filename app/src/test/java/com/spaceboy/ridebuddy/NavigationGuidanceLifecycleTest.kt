package com.spaceboy.ridebuddy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationGuidanceLifecycleTest {
    @Test
    fun `intermediate arrival advances to the next destination`() {
        val events = mutableListOf<String>()
        val lifecycle = lifecycle(events)
        val session = FakeNavigationGuidanceSession()
        lifecycle.registerPendingSession(1L)
        assertTrue(lifecycle.attach(1L, session) { events += "ui" })

        session.arrive(isFinalDestination = false)

        assertEquals(1, session.continueCalls)
        assertEquals(0, session.stopCalls)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `final arrival stops guidance and clears app and cluster state`() {
        val events = mutableListOf<String>()
        val lifecycle = lifecycle(events)
        val session = FakeNavigationGuidanceSession()
        lifecycle.registerPendingSession(1L)
        lifecycle.attach(1L, session) { events += "ui" }

        session.arrive(isFinalDestination = true)

        assertEquals(1, session.stopCalls)
        assertEquals(1, session.unregisterCalls)
        assertEquals(listOf("tft", "feed", "ui"), events)
        assertEquals(0, session.cleanupCalls)
    }

    @Test
    fun `background arrival is handled and releases the navigator`() {
        val events = mutableListOf<String>()
        val lifecycle = lifecycle(events)
        val session = FakeNavigationGuidanceSession()
        lifecycle.registerPendingSession(1L)
        lifecycle.attach(1L, session) { events += "ui" }
        lifecycle.detachUi(1L)
        assertNotNull(session.speedHandler)

        session.arrive(isFinalDestination = true)

        assertEquals(listOf("tft", "feed"), events)
        assertEquals(1, session.stopCalls)
        assertEquals(1, session.unregisterCalls)
        assertEquals(1, session.cleanupCalls)
        assertNull(session.handler)
        assertNull(session.speedHandler)
    }

    @Test
    fun `newer pending session protects shared navigator from stale cleanup`() {
        val events = mutableListOf<String>()
        val lifecycle = lifecycle(events)
        val session = FakeNavigationGuidanceSession()
        lifecycle.registerPendingSession(1L)
        lifecycle.attach(1L, session) { events += "old-ui" }
        lifecycle.detachUi(1L)
        lifecycle.registerPendingSession(2L)

        session.arrive(isFinalDestination = true)

        assertEquals(0, session.cleanupCalls)
        assertFalse(lifecycle.attach(1L, FakeNavigationGuidanceSession()) {})
        assertTrue(lifecycle.attach(2L, session) { events += "new-ui" })
        assertEquals(0, session.cleanupCalls)
    }

    @Test
    fun `external stop detaches the retained arrival listener`() {
        val lifecycle = lifecycle(mutableListOf())
        val session = FakeNavigationGuidanceSession()
        lifecycle.registerPendingSession(1L)
        lifecycle.attach(1L, session) {}

        assertTrue(lifecycle.release(session.identity))

        assertNull(session.handler)
        assertNull(session.speedHandler)
        assertFalse(lifecycle.release(session.identity))
    }

    @Test
    fun `stale terminal feed cannot stop a pending preparing or running route`() {
        val lifecycle = lifecycle(mutableListOf())
        val oldSession = FakeNavigationGuidanceSession().apply { guidanceRunning = true }
        lifecycle.registerPendingSession(1L)
        lifecycle.attach(1L, oldSession) {}
        lifecycle.markGuidanceStarted(1L)

        lifecycle.registerPendingSession(2L)
        assertFalse(lifecycle.acceptAndMarkTerminalFeed())

        val newSession = FakeNavigationGuidanceSession()
        lifecycle.attach(2L, newSession) {}
        assertFalse(lifecycle.acceptAndMarkTerminalFeed())

        newSession.guidanceRunning = true
        lifecycle.markGuidanceStarted(2L)
        assertFalse(lifecycle.acceptAndMarkTerminalFeed())
    }

    @Test
    fun `terminal feed stops an actually ended route but not finalized arrival`() {
        val lifecycle = lifecycle(mutableListOf())
        val session = FakeNavigationGuidanceSession().apply { guidanceRunning = true }
        lifecycle.registerPendingSession(1L)
        lifecycle.attach(1L, session) {}
        lifecycle.markGuidanceStarted(1L)
        assertFalse(lifecycle.acceptAndMarkTerminalFeed())

        session.guidanceRunning = false
        assertTrue(lifecycle.acceptAndMarkTerminalFeed())

        session.guidanceRunning = true
        session.arrive(isFinalDestination = true)
        assertFalse(lifecycle.acceptAndMarkTerminalFeed())
    }

    @Test
    fun `accepted terminal releases retained navigator exactly once after ui detaches`() {
        val lifecycle = lifecycle(mutableListOf())
        val session = FakeNavigationGuidanceSession().apply { guidanceRunning = true }
        lifecycle.registerPendingSession(1L)
        lifecycle.attach(1L, session) {}
        lifecycle.markGuidanceStarted(1L)
        assertNotNull(session.handler)
        assertNotNull(session.speedHandler)

        session.guidanceRunning = false
        assertTrue(lifecycle.acceptAndMarkTerminalFeed())
        assertEquals(0, session.cleanupCalls)

        lifecycle.detachUi(1L)
        lifecycle.detachUi(1L)

        assertNull(session.handler)
        assertNull(session.speedHandler)
        assertEquals(1, session.cleanupCalls)
        assertFalse(lifecycle.release(session.identity))
    }

    private fun lifecycle(events: MutableList<String>) = NavigationGuidanceLifecycle(
        clearNavigationFeed = { events += "feed" },
        finishTftArrival = { events += "tft" },
    )

    private class FakeNavigationGuidanceSession : NavigationGuidanceSession {
        override val identity: Any = Any()
        override val isGuidanceRunning: Boolean
            get() = guidanceRunning
        var guidanceRunning = false
        var handler: ((Boolean) -> Unit)? = null
        var continueCalls = 0
        var stopCalls = 0
        var unregisterCalls = 0
        var cleanupCalls = 0
        var speedHandler: ((Float) -> Unit)? = null

        override fun setArrivalHandler(handler: ((Boolean) -> Unit)?) {
            this.handler = handler
        }

        override fun setSpeedingHandler(handler: ((Float) -> Unit)?) {
            speedHandler = handler
        }

        override fun continueToNextDestination() {
            continueCalls++
        }

        override fun stopGuidance() {
            stopCalls++
            guidanceRunning = false
        }

        override fun unregisterServiceForNavUpdates() {
            unregisterCalls++
        }

        override fun cleanup() {
            cleanupCalls++
        }

        fun arrive(isFinalDestination: Boolean) {
            handler?.invoke(isFinalDestination)
        }
    }
}
