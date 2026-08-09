package com.spaceboy.ridebuddy.data

import com.spaceboy.ridebuddy.ble.TelemetryFrame
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

    @Test
    fun `integrates fuel by distance instead of telemetry sample count`() {
        val completed = ActiveRide.started(0L, 0L, frame(speed = 20.0, consumption = 10.0))
            .add(frame(speed = 20.0, consumption = 10.0), receivedAtElapsedRealtime = 1_000L, distanceDelta = 20.0)
            .add(frame(speed = 100.0, consumption = 5.0), receivedAtElapsedRealtime = 2_000L, distanceDelta = 0.0)
            .add(frame(speed = 100.0, consumption = 5.0), receivedAtElapsedRealtime = 3_000L, distanceDelta = 100.0)
            .toRide(4_000L)

        assertEquals(7.0, completed.estimatedFuelLitres, 0.000_001)
        assertEquals(5.833_333, completed.averageConsumptionLPer100Km, 0.000_001)
    }

    @Test
    fun `confirmed stop time is preserved while an immediate finish uses completion time`() {
        assertEquals(2_000L, completedRideEndMillis(2_000L, 12_000L))
        assertEquals(12_000L, completedRideEndMillis(null, 12_000L))
    }

    private fun frame(speed: Double, consumption: Double) = TelemetryFrame(
        speedKilometresPerHour = speed,
        throttlePercent = 10,
        instantaneousConsumptionLitresPer100Km = consumption,
        engineRpm = 3_000,
    )
}
