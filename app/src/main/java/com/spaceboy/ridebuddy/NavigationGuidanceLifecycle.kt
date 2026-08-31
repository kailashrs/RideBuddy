package com.spaceboy.ridebuddy

import com.google.android.libraries.navigation.Navigator
import com.google.android.libraries.navigation.SpeedingListener

/**
 * Owns arrival handling for the process, independently of the map Activity.
 *
 * Guidance keeps running when the rider backgrounds the map — that is the normal case, with
 * the phone stowed — so arrival has to be handled somewhere that outlives the Activity.
 * When the map goes away its callbacks are detached while the navigator binding is kept, and
 * the navigator is only cleaned up once nothing is left that could still use it.
 *
 * Two identifiers guard against acting on a superseded session. [Binding.sessionId] is this
 * app's own counter, and `identity` is the navigator instance itself; both must match before
 * any callback is honoured, since the SDK's callbacks carry neither. [pendingSessions]
 * covers the window where a newer session has been requested but its navigator has not
 * arrived yet — during which an older session's arrival must not be treated as current.
 */
internal class NavigationGuidanceLifecycle(
    private val clearNavigationFeed: () -> Unit,
    private val finishTftArrival: () -> Unit,
) {
    private val lock = Any()
    private val pendingSessions = mutableSetOf<Long>()
    private var binding: Binding? = null

    /**
     * Declares that a session has been requested but not yet bound. Older pending ids are
     * dropped: they have been superseded, and only the newest request can still complete.
     */
    fun registerPendingSession(sessionId: Long) {
        synchronized(lock) {
            pendingSessions.removeAll { it < sessionId }
            pendingSessions += sessionId
        }
    }

    /** A requested session never materialised. May release a navigator that was only being
     *  kept alive on its behalf. */
    fun abandonPendingSession(sessionId: Long) {
        val cleanup = synchronized(lock) {
            pendingSessions.remove(sessionId)
            takeCompletedBackgroundSessionIfUnusedLocked()
        }
        cleanup?.detachAndCleanup()
    }

    fun attach(
        sessionId: Long,
        navigator: Navigator,
        onSpeeding: (Float) -> Unit,
        onFinalArrival: () -> Unit,
    ): Boolean = attach(
        sessionId = sessionId,
        session = NavigatorGuidanceSession(navigator),
        onSpeeding = onSpeeding,
        onFinalArrival = onFinalArrival,
    )

    /**
     * Binds a session's callbacks, returning false when a newer session has already been
     * requested and this one is stale on arrival.
     *
     * Re-attaching the *same* navigator — the Activity returning to the foreground — keeps
     * the existing session object and only swaps the callbacks, so the arrival state built
     * up while it was backgrounded survives. A different navigator replaces the binding and
     * detaches the old one's handlers.
     */
    internal fun attach(
        sessionId: Long,
        session: NavigationGuidanceSession,
        onSpeeding: (Float) -> Unit = {},
        onFinalArrival: () -> Unit,
    ): Boolean {
        var previous: NavigationGuidanceSession? = null
        var install: NavigationGuidanceSession? = null
        synchronized(lock) {
            if (pendingSessions.any { it > sessionId }) return false
            pendingSessions.removeAll { it <= sessionId }
            val current = binding
            if (current?.session?.identity === session.identity) {
                binding = Binding(
                    sessionId = sessionId,
                    session = current.session,
                    onFinalArrival = onFinalArrival,
                )
                install = current.session
            } else {
                previous = current?.session
                binding = Binding(sessionId, session, onFinalArrival)
                install = session
            }
        }
        previous?.setArrivalHandler(null)
        previous?.setSpeedingHandler(null)
        install?.let { installedSession ->
            installedSession.setArrivalHandler { isFinalDestination ->
                handleArrival(sessionId, installedSession.identity, isFinalDestination)
            }
            installedSession.setSpeedingHandler(onSpeeding)
        }
        return true
    }

    /**
     * The map is going away. Clears the UI callback while keeping the navigator bound, so
     * guidance continues in the background; releases it only if it is already finished.
     */
    fun detachUi(sessionId: Long) {
        val cleanup = synchronized(lock) {
            val current = binding
            if (current?.sessionId == sessionId) {
                binding = current.copy(onFinalArrival = null)
            }
            takeCompletedBackgroundSessionIfUnusedLocked()
        }
        cleanup?.detachAndCleanup()
    }

    /**
     * Guidance is actually running. The route version is bumped so a terminal update from
     * the previous route cannot be mistaken for this one ending.
     */
    fun markGuidanceStarted(sessionId: Long) {
        synchronized(lock) {
            val current = binding
            if (current?.sessionId == sessionId && !current.finalized) {
                binding = current.copy(
                    guidanceStarted = true,
                    terminalEnded = false,
                    routeVersion = current.routeVersion + 1,
                )
            }
        }
    }

    /**
     * Decides whether a terminal guidance update genuinely ends the current route.
     *
     * These messages carry no session token and can be left over from an older service
     * registration, so they are checked against the retained navigator instead of trusted.
     * The check spans two locked sections because the decisive part — asking the navigator
     * whether guidance is still running — is an SDK call that must not be made under the
     * lock. Everything read in the first section is therefore re-verified in the second,
     * and any change means another route started underneath and this update is stale.
     */
    fun acceptAndMarkTerminalFeed(): Boolean {
        val candidate = synchronized(lock) {
            if (pendingSessions.isNotEmpty()) return false
            val current = binding ?: return true
            if (current.finalized || current.terminalEnded || !current.guidanceStarted) return false
            TerminalCandidate(
                sessionId = current.sessionId,
                session = current.session,
                routeVersion = current.routeVersion,
            )
        }
        if (candidate.session.isGuidanceRunning) return false

        return synchronized(lock) {
            if (pendingSessions.isNotEmpty()) return false
            val current = binding ?: return true
            if (current.sessionId != candidate.sessionId ||
                current.session.identity !== candidate.session.identity ||
                current.routeVersion != candidate.routeVersion ||
                current.finalized || current.terminalEnded || !current.guidanceStarted
            ) return false
            binding = current.copy(
                guidanceStarted = false,
                terminalEnded = true,
            )
            true
        }
    }

    /** Releases a specific session's binding. No-op if it is not the one bound. */
    internal fun release(sessionId: Long, identity: Any): Boolean {
        val released = synchronized(lock) {
            val current = binding
            if (current?.sessionId != sessionId || current.session.identity !== identity) return false
            binding = null
            pendingSessions.remove(sessionId)
            current.session
        }
        released.setArrivalHandler(null)
        released.setSpeedingHandler(null)
        return true
    }

    /** Releases by navigator alone, for a stop that has no session id to hand. */
    internal fun release(identity: Any): Boolean {
        val released = synchronized(lock) {
            val current = binding
            if (current?.session?.identity !== identity) return false
            binding = null
            current.session
        }
        released.setArrivalHandler(null)
        released.setSpeedingHandler(null)
        return true
    }

    /**
     * Handles an arrival. A waypoint simply advances the route; the final destination stops
     * guidance and tears the session down.
     */
    private fun handleArrival(sessionId: Long, identity: Any, isFinalDestination: Boolean) {
        if (!isFinalDestination) {
            val session = synchronized(lock) {
                binding?.takeIf { it.sessionId == sessionId && it.session.identity === identity }?.session
            } ?: return
            runCatching(session::continueToNextDestination)
            return
        }

        val completed = synchronized(lock) {
            val current = binding ?: return
            if (current.sessionId != sessionId || current.session.identity !== identity || current.finalized) return
            current.copy(finalized = true).also { binding = it }
        }
        // Arm the cluster arrival state before stopGuidance can emit a terminal NavInfo update.
        runCatching(finishTftArrival)
        runCatching(completed.session::stopGuidance)
        runCatching(completed.session::unregisterServiceForNavUpdates)
        completed.session.setSpeedingHandler(null)
        runCatching(clearNavigationFeed)
        runCatching { completed.onFinalArrival?.invoke() }

        val cleanup = synchronized(lock) { takeCompletedBackgroundSessionIfUnusedLocked() }
        cleanup?.detachAndCleanup()
    }

    /**
     * Claims a finished session's navigator for cleanup, but only when nothing can still use
     * it: it must have ended, have no UI attached, and not be holding a place for a newer
     * session still being set up.
     */
    private fun takeCompletedBackgroundSessionIfUnusedLocked(): NavigationGuidanceSession? {
        val current = binding ?: return null
        if ((!current.finalized && !current.terminalEnded) || current.onFinalArrival != null ||
            pendingSessions.any { it > current.sessionId }
        ) return null
        binding = null
        return current.session
    }

    private fun NavigationGuidanceSession.detachAndCleanup() {
        setArrivalHandler(null)
        setSpeedingHandler(null)
        runCatching(::cleanup)
    }

    /**
     * The currently bound session.
     *
     * [onFinalArrival] is null while no UI is attached — guidance still runs, there is just
     * nothing to notify. [finalized] means arrival was handled; [terminalEnded] means the
     * feed reported the route over. Either allows cleanup, but they are distinct states and
     * only [finalized] suppresses further arrival handling.
     */
    private data class Binding(
        val sessionId: Long,
        val session: NavigationGuidanceSession,
        val onFinalArrival: (() -> Unit)?,
        val guidanceStarted: Boolean = false,
        val terminalEnded: Boolean = false,
        val routeVersion: Long = 0L,
        val finalized: Boolean = false,
    )

    private data class TerminalCandidate(
        val sessionId: Long,
        val session: NavigationGuidanceSession,
        val routeVersion: Long,
    )
}

/**
 * The navigator surface this class uses, behind an interface so the lifecycle rules can be
 * exercised without the SDK. [identity] is the underlying instance, compared by reference
 * to recognise a re-attach of the same navigator.
 */
internal interface NavigationGuidanceSession {
    val identity: Any
    fun setArrivalHandler(handler: ((Boolean) -> Unit)?)
    fun setSpeedingHandler(handler: ((Float) -> Unit)?)
    val isGuidanceRunning: Boolean
    fun continueToNextDestination()
    fun stopGuidance()
    fun unregisterServiceForNavUpdates()
    fun cleanup()
}

/** The real implementation. Holds its own arrival listener so it can be removed again. */
private class NavigatorGuidanceSession(
    private val navigator: Navigator,
) : NavigationGuidanceSession {
    private var arrivalListener: Navigator.ArrivalListener? = null

    override val identity: Any = navigator
    override val isGuidanceRunning: Boolean
        get() = navigator.isGuidanceRunning

    override fun setArrivalHandler(handler: ((Boolean) -> Unit)?) {
        arrivalListener?.let(navigator::removeArrivalListener)
        arrivalListener = handler?.let { callback ->
            Navigator.ArrivalListener { event -> callback(event.isFinalDestination) }
        }
        arrivalListener?.let(navigator::addArrivalListener)
    }

    override fun setSpeedingHandler(handler: ((Float) -> Unit)?) {
        navigator.setSpeedingListener(
            handler?.let { callback ->
                SpeedingListener { percentageAboveLimit, _ ->
                    callback(percentageAboveLimit)
                }
            },
        )
    }

    override fun continueToNextDestination() {
        navigator.continueToNextDestination()
    }

    override fun stopGuidance() = navigator.stopGuidance()

    override fun unregisterServiceForNavUpdates() {
        navigator.unregisterServiceForNavUpdates()
    }

    override fun cleanup() = navigator.cleanup()
}
