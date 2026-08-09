package com.spaceboy.ridebuddy.data

import org.junit.Assert.assertEquals
import org.junit.Test

class RideEventDetectorTest {
    @Test
    fun detectsOnlySamplesAtOrBeyondThreshold() {
        val events = RideEventDetector.detect(
            listOf(sample(2.9), sample(3.0), sample(-4.0)),
        )

        assertEquals(listOf(RideEventType.HardAcceleration, RideEventType.HardBraking), events.map(RideEvent::type))
    }

    @Test
    fun groupsConsecutiveThresholdSamplesIntoOnePeakEvent() {
        val events = RideEventDetector.detect(
            listOf(
                sample(3.1, timestamp = 1_000),
                sample(4.5, timestamp = 1_100),
                sample(3.8, timestamp = 1_200),
            ),
        )

        assertEquals(1, events.size)
        assertEquals(1_100L, events.single().timestampMillis)
        assertEquals(4.5, events.single().accelerationMetresPerSecondSquared, 0.0)
    }

    @Test
    fun normalSampleSeparatesDistinctEpisodes() {
        val events = RideEventDetector.detect(
            listOf(
                sample(-4.0, timestamp = 1_000),
                sample(0.0, timestamp = 1_100),
                sample(-4.5, timestamp = 1_200),
            ),
        )

        assertEquals(2, events.size)
    }

    private fun sample(acceleration: Double, timestamp: Long = 1) = RideSample(
        timestampMillis = timestamp,
        speedKph = 10.0,
        rpm = 3_000,
        throttlePercent = 10,
        consumptionLPer100Km = 4.0,
        accelerationMetresPerSecondSquared = acceleration,
    )
}
