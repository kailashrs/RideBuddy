package com.spaceboy.ridebuddy

import kotlin.time.Duration.Companion.milliseconds
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stop path had no coverage while it owned the trickiest concurrency in the app: an SDK
 * handoff that may call back, error, throw, or say nothing at all, raced against a timeout,
 * and a guard that a newer request can take over mid-flight. It was untestable because
 * `NavigationApi.getNavigator` is static; [NavigatorHandoff] is the seam that fixed that.
 *
 * Each of those four handoff outcomes gets a test, plus the two that made the timeout
 * necessary in the first place: a late callback must not produce a second result, and a
 * request that is never answered must not strand the guard.
 */
class NavigationStopControllerTest {
    @Test
    fun `a ready navigator stops guidance, cleans up and clears the cluster`() {
        val world = World()
        val session = FakeSession()

        val result = world.stopAndAwait { onReady, _ -> onReady(session) }

        assertEquals(NavigationStopResult.Stopped, result)
        assertEquals(1, session.stopCalls)
        assertEquals(1, session.unregisterCalls)
        assertEquals(1, session.cleanupCalls)
        assertEquals(1, world.clearOutputCalls)
        assertTrue("the guard must be released", world.guardIsFree())
    }

    @Test
    fun `a cleanup failure is reported but still clears the cluster`() {
        val world = World()
        val session = FakeSession(failCleanup = true)

        val result = world.stopAndAwait { onReady, _ -> onReady(session) }

        assertEquals(NavigationStopResult.CleanupIncomplete, result)
        // The route is already stopped; a cluster still showing it would be worse than an
        // untidy teardown.
        assertEquals(1, world.clearOutputCalls)
        assertTrue(world.guardIsFree())
    }

    @Test
    fun `a failure to stop guidance leaves the cluster showing the live route`() {
        val world = World()
        val session = FakeSession(failStop = true)

        val result = world.stopAndAwait { onReady, _ -> onReady(session) }

        assertEquals(NavigationStopResult.Failed, result)
        // Guidance may well still be running. A display cleared out from under a live route
        // would leave the rider navigating with no prompts and no indication why.
        assertEquals(0, world.clearOutputCalls)
        assertTrue(world.guardIsFree())
    }

    @Test
    fun `an errored handoff fails without touching the cluster`() {
        val world = World()

        val result = world.stopAndAwait { _, onError -> onError() }

        assertEquals(NavigationStopResult.Failed, result)
        assertEquals(0, world.clearOutputCalls)
        assertTrue(world.guardIsFree())
    }

    @Test
    fun `a handoff that throws instead of calling back fails`() {
        val world = World()

        val result = world.stopAndAwait { _, _ -> throw IllegalStateException("SDK not initialised") }

        assertEquals(NavigationStopResult.Failed, result)
        assertEquals(0, world.clearOutputCalls)
        assertTrue(world.guardIsFree())
    }

    @Test
    fun `a handoff that never answers times out rather than stranding the guard`() {
        val world = World()

        // Never calls back. Before the timeout existed this left stopInProgress set forever,
        // after which every later press of the handlebar EXIT returned AlreadyStopping.
        val result = world.stopAndAwait { _, _ -> }

        assertEquals(NavigationStopResult.Failed, result)
        assertEquals(0, world.clearOutputCalls)
        assertTrue("a later stop must be able to proceed", world.guardIsFree())
    }

    @Test
    fun `a callback arriving after the timeout produces no second result`() {
        val world = World()
        val late = CapturedHandoff()
        val session = FakeSession()

        val first = world.stopAndAwait(late)
        assertEquals(NavigationStopResult.Failed, first)

        late.onReady?.invoke(session)

        assertEquals(1, world.results.size)
        assertEquals(0, session.stopCalls)
        assertEquals(0, world.clearOutputCalls)
    }

    @Test
    fun `a handoff that answers twice acts on the first answer only`() {
        val world = World()
        val first = FakeSession()
        val second = FakeSession()

        // The SDK's contract is at most one callback. This is the defensive case the old
        // AtomicBoolean covered and the continuation covers now.
        val result = world.stopAndAwait { onReady, _ ->
            onReady(first)
            onReady(second)
        }

        assertEquals(NavigationStopResult.Stopped, result)
        assertEquals(1, first.stopCalls)
        assertEquals(0, second.stopCalls)
        assertEquals(1, world.clearOutputCalls)
    }

    @Test
    fun `a handoff that both succeeds and errors keeps the success`() {
        val world = World()
        val session = FakeSession()

        val result = world.stopAndAwait { onReady, onError ->
            onReady(session)
            onError()
        }

        assertEquals(NavigationStopResult.Stopped, result)
        assertEquals(1, world.clearOutputCalls)
    }

    @Test
    fun `a second stop while one is in flight is rejected, synchronously`() {
        val world = World()
        val held = CapturedHandoff()
        world.controller(held).stop(world.results::add)

        var secondResult: NavigationStopResult? = null
        world.controller(held).stop { secondResult = it }

        // Synchronous matters: the disconnect path stops navigation and then tears the
        // connection down, and relies on the stop being registered by then.
        assertEquals(NavigationStopResult.AlreadyStopping, secondResult)
        assertTrue(world.results.isEmpty())
    }

    @Test
    fun `a start superseding the stop reports nothing, because it no longer owns the outcome`() {
        val world = World()
        val held = CapturedHandoff()
        val session = FakeSession()
        world.controller(held).stop(world.results::add)

        // A new route begins while the handoff is still pending.
        world.guard.beginStart()
        held.onReady?.invoke(session)

        assertTrue("the superseded stop must stay silent", world.results.isEmpty())
        assertEquals(0, session.stopCalls)
        assertEquals(0, world.clearOutputCalls)
    }

    @Test
    fun `the guard is claimed before stop returns, not when the coroutine runs`() {
        val world = World()
        val held = CapturedHandoff()
        val dispatcher = QueueingDispatcher()

        world.controller(held, dispatcher).stop(world.results::add)

        // Nothing has been dispatched yet, so the handoff has not even been requested --
        // and the guard is nonetheless already claimed. The disconnect path stops navigation
        // and then tears the connection down, and relies on exactly this ordering.
        assertNull(held.onReady)
        assertNull(world.guard.beginStop())

        dispatcher.runQueued()
        assertNotNull(held.onReady)
    }

    @Test
    fun `the callback form reports the same outcome as the suspending one`() {
        val world = World()
        val session = FakeSession()

        world.controller(NavigatorHandoff { onReady, _ -> onReady(session) }).stop(world.results::add)

        assertEquals(listOf(NavigationStopResult.Stopped), world.results)
    }

    private class World {
        val guard = NavigationStartStopGuard()
        val results = mutableListOf<NavigationStopResult>()
        var clearOutputCalls = 0
        private val lifecycle = NavigationGuidanceLifecycle(
            clearNavigationFeed = {},
            finishTftArrival = {},
        )

        fun controller(
            handoff: NavigatorHandoff,
            dispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
        ) = NavigationStopController(
            guard = guard,
            guidanceLifecycle = lifecycle,
            clearOutput = { clearOutputCalls++ },
            // Unconfined by default so a handoff that answers immediately settles without a
            // scheduler, which keeps most assertions about ordering rather than timing. The
            // ordering test overrides it, because Unconfined runs a launch eagerly and so
            // cannot tell a synchronous claim from one made inside the coroutine.
            scope = CoroutineScope(dispatcher),
            handoff = handoff,
            // Real time, but short. The alternative is a test dependency for virtual time,
            // and the timeout is the one thing here that has to elapse for real.
            handoffTimeout = 50.milliseconds,
        )

        fun stopAndAwait(handoff: NavigatorHandoff): NavigationStopResult? =
            runBlocking { controller(handoff).stopAndAwait() }.also { it?.let(results::add) }

        /** True when a later stop can still be started. */
        fun guardIsFree(): Boolean = guard.beginStop() != null
    }

    /** Runs nothing until asked, so a test can observe the moment before the coroutine starts. */
    private class QueueingDispatcher : CoroutineDispatcher() {
        private val queued = ArrayDeque<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            queued += block
        }

        fun runQueued() {
            while (queued.isNotEmpty()) queued.removeFirst().run()
        }
    }

    /** Holds the callbacks so a test can answer late, or never. */
    private class CapturedHandoff : NavigatorHandoff {
        var onReady: ((NavigationGuidanceSession) -> Unit)? = null
        var onError: (() -> Unit)? = null

        override fun request(onReady: (NavigationGuidanceSession) -> Unit, onError: () -> Unit) {
            this.onReady = onReady
            this.onError = onError
        }
    }

    private class FakeSession(
        private val failStop: Boolean = false,
        private val failCleanup: Boolean = false,
    ) : NavigationGuidanceSession {
        override val identity: Any = Any()
        override val isGuidanceRunning: Boolean = false
        var stopCalls = 0
        var unregisterCalls = 0
        var cleanupCalls = 0

        override fun setArrivalHandler(handler: ((Boolean) -> Unit)?) = Unit
        override fun continueToNextDestination() = Unit

        override fun stopGuidance() {
            stopCalls++
            if (failStop) throw IllegalStateException("guidance would not stop")
        }

        override fun unregisterServiceForNavUpdates() {
            unregisterCalls++
        }

        override fun cleanup() {
            cleanupCalls++
            if (failCleanup) throw IllegalStateException("cleanup failed")
        }
    }
}
