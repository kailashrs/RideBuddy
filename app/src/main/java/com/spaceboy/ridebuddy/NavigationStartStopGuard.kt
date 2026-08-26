package com.spaceboy.ridebuddy

internal class NavigationStartStopGuard {
    private var generation = 0L
    private var stopInProgress = false

    @Synchronized
    fun beginStart(): Long {
        stopInProgress = false
        return ++generation
    }

    @Synchronized
    fun beginStop(): Long? {
        if (stopInProgress) return null
        stopInProgress = true
        return ++generation
    }

    @Synchronized
    fun isCurrentStop(requestId: Long): Boolean = stopInProgress && generation == requestId

    @Synchronized
    fun finishStop(requestId: Long) {
        if (generation == requestId) stopInProgress = false
    }
}
