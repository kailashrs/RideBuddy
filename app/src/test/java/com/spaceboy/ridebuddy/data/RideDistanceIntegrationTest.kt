package com.spaceboy.ridebuddy.data

import org.junit.Assert.assertEquals
import org.junit.Test

class RideDistanceIntegrationTest {
    @Test
    fun `integrates a normal telemetry interval`() {
        assertEquals(
            0.025,
            distanceDeltaKilometres(lastSpeedKph = 36.0, currentSpeedKph = 36.0, elapsedMillis = 2_500L),
            0.000_001,
        )
    }

    @Test
    fun `does not invent distance across a telemetry gap`() {
        assertEquals(
            0.0,
            distanceDeltaKilometres(lastSpeedKph = 120.0, currentSpeedKph = 120.0, elapsedMillis = 10_000L),
            0.0,
        )
    }

    @Test
    fun `zero stop threshold matches a stopped bike`() {
        assertEquals(true, shouldStopRide(speedKph = 0.0, stopSpeedKph = 0.0))
        assertEquals(false, shouldStopRide(speedKph = 0.01, stopSpeedKph = 0.0))
    }
}
