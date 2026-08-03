package com.spaceboy.ridebuddy.data

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull

class RidePerformanceTest {
    @Test
    fun measuresLaunchToTarget() {
        val samples = listOf(sample(0, 2.0), sample(1_000, 20.0), sample(2_000, 45.0), sample(3_500, 61.0))
        assertEquals(3_406L, samples.accelerationTime(60.0))
    }

    @Test
    fun requiresTargetToBeReached() {
        assertNull(listOf(sample(0, 1.0), sample(2_000, 55.0)).accelerationTime(60.0))
    }

    @Test
    fun rejectsMetricsAcrossLargeTelemetryGaps() {
        assertNull(listOf(sample(0, 2.0), sample(5_000, 65.0)).accelerationTime(60.0))
    }

    private fun sample(timestamp: Long, speed: Double) = RideSample(timestamp, speed, 1_000, 10, 5.0, 0.0)
}
