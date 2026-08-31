package com.spaceboy.ridebuddy

/**
 * Serialises navigation start and stop requests.
 *
 * Both are asynchronous — each waits on the SDK to hand back a navigator — and both can be
 * triggered from several places at once: the UI, the handlebar EXIT button, a shared
 * destination arriving. Without a guard, a stop completing after a start had already begun
 * would tear down the route that start had just created.
 *
 * A single generation counter covers both. Any new request supersedes whatever was pending,
 * and a callback whose generation has moved on returns without acting.
 */
internal class NavigationStartStopGuard {
    private var generation = 0L
    private var stopInProgress = false

    /** Claims the next generation for a start, invalidating any stop still in flight. */
    @Synchronized
    fun beginStart(): Long {
        stopInProgress = false
        return ++generation
    }

    /** Claims a generation for a stop, or null when one is already in progress. */
    @Synchronized
    fun beginStop(): Long? {
        if (stopInProgress) return null
        stopInProgress = true
        return ++generation
    }

    /** Whether a stop callback still owns the outcome, or has been superseded. */
    @Synchronized
    fun isCurrentStop(requestId: Long): Boolean = stopInProgress && generation == requestId

    /** Releases the stop, unless a newer request has already taken over. */
    @Synchronized
    fun finishStop(requestId: Long) {
        if (generation == requestId) stopInProgress = false
    }
}
