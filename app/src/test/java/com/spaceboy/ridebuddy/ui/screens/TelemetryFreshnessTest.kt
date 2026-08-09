package com.spaceboy.ridebuddy.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryFreshnessTest {
    @Test
    fun freshnessUsesMonotonicElapsedTimeAndRejectsFutureOrOldFrames() {
        assertTrue(isTelemetryFresh(receivedAtElapsedRealtime = 10_000L, nowElapsedRealtime = 15_000L))
        assertFalse(isTelemetryFresh(receivedAtElapsedRealtime = 10_000L, nowElapsedRealtime = 15_001L))
        assertFalse(isTelemetryFresh(receivedAtElapsedRealtime = 20_000L, nowElapsedRealtime = 15_000L))
    }
}
