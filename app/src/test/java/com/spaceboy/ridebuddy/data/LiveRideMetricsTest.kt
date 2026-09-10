package com.spaceboy.ridebuddy.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveRideMetricsTest {
    @Test
    fun `calculates event counts and packet gaps away from composition`() {
        val metrics = calculateLiveRideMetrics(
            listOf(
                sample(timestampMillis = 0L, acceleration = 3.1),
                sample(timestampMillis = 250L, acceleration = -3.6),
                sample(timestampMillis = 500L, acceleration = 0.0),
                sample(timestampMillis = 1_000L, acceleration = 0.0),
            ),
        )

        assertEquals(1, metrics.hardAccelerationEvents)
        assertEquals(1, metrics.hardBrakingEvents)
        assertEquals(20, metrics.estimatedPacketGapPercent)
    }

    @Test
    fun `packet gap estimate waits for a useful sample window`() {
        assertNull(calculateLiveRideMetrics(listOf(sample(0L, 0.0))).estimatedPacketGapPercent)
    }

    @Test
    fun sustainedAccelerationCountsAsOneManeuver() {
        val metrics = calculateLiveRideMetrics(listOf(sample(0, 3.1), sample(250, 4.0), sample(500, 3.2), sample(750, 0.0)))
        assertEquals(1, metrics.hardAccelerationEvents)
    }

    @Test
    fun wallClockReversalCannotInventPacketLoss() {
        assertNull(calculateLiveRideMetrics(listOf(sample(0, 0.0), sample(250, 0.0), sample(100, 0.0), sample(500, 0.0))).estimatedPacketGapPercent)
    }

    private fun sample(timestampMillis: Long, acceleration: Double) = RideSample(
        timestampMillis = timestampMillis,
        speedKph = 0.0,
        rpm = 0L,
        throttlePercent = 0,
        mileageKilometresPerLitre = null,
        accelerationMetresPerSecondSquared = acceleration,
    )
}
