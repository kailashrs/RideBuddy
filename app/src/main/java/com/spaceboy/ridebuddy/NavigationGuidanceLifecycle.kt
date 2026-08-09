package com.spaceboy.ridebuddy

import com.google.android.libraries.navigation.Navigator

/**
 * Owns arrival handling independently of the map Activity so final arrival is still processed
 * after the user backgrounds the map. UI callbacks are detached while the Navigator listener is
 * retained for the active background session.
 */
internal class NavigationGuidanceLifecycle(
    private val clearNavigationFeed: () -> Unit,
    private val finishTftArrival: () -> Unit,
) {
    private val lock = Any()
    private val pendingSessions = mutableSetOf<Long>()
    private var binding: Binding? = null

    fun registerPendingSession(sessionId: Long) {
        synchronized(lock) {
            pendingSessions.removeAll { it < sessionId }
            pendingSessions += sessionId
        }
    }

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
     * A terminal NavInfo message has no session token and can be left over from an older service
     * registration. Accept it only when it agrees with the retained Navigator lifecycle, and mark
     * that binding ended so a later UI detach releases the process-owned Navigator.
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
        if (runCatching { candidate.session.isGuidanceRunning }.getOrDefault(true)) return false

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

    fun release(sessionId: Long, navigator: Navigator): Boolean =
        release(sessionId, navigator as Any)

    fun release(navigator: Navigator): Boolean = release(navigator as Any)

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
                com.google.android.libraries.navigation.SpeedingListener { percentageAboveLimit, _ ->
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
