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

    private fun sample(acceleration: Double) = RideSample(
        timestampMillis = 1,
        speedKph = 10.0,
        rpm = 3_000,
        throttlePercent = 10,
        consumptionLPer100Km = 4.0,
        accelerationMetresPerSecondSquared = acceleration,
    )
}
