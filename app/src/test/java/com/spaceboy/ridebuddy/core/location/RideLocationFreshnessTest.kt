package com.spaceboy.ridebuddy.core.location

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RideLocationFreshnessTest {
    @Test
    fun acceptsOnlyRecentMonotonicLocationFixes() {
        val location = RideLocation(
            latitude = 12.9716,
            longitude = 77.5946,
            accuracyMetres = 5f,
            altitudeMetres = null,
            fixElapsedRealtimeMillis = 10_000L,
        )

        assertTrue(location.isFreshAt(nowElapsedRealtimeMillis = 40_000L))
        assertFalse(location.isFreshAt(nowElapsedRealtimeMillis = 40_001L))
    }
}
