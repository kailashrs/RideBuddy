package com.spaceboy.ridebuddy.data

import com.spaceboy.ridebuddy.ble.TelemetryFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RideDistanceIntegrationTest {
    @Test fun `integrates a normal telemetry interval`() {
        assertEquals(0.025, distanceDeltaKilometres(36.0, 36.0, 2_500L), 0.000_001)
    }

    @Test fun `unmeasured or invalid intervals never invent distance`() {
        listOf(0L, -1L, 10_000L).forEach {
            assertEquals(0.0, distanceDeltaKilometres(120.0, 120.0, it), 0.0)
        }
        assertEquals(0.0, distanceDeltaKilometres(Double.NaN, 120.0, 250L), 0.0)
        assertEquals(0.0, distanceDeltaKilometres(-10.0, 120.0, 250L), 0.0)
    }

    @Test fun `zero stop threshold matches a stopped bike`() {
        assertEquals(true, shouldStopRide(0.0, 0.0))
        assertEquals(false, shouldStopRide(0.01, 0.0))
    }

    @Test fun `decodes bike mileage and integrates litres by distance`() {
        val rawFrame = byteArrayOf(0x10, 0x10, 0x0E, 10, 125, 0x10, 0x27, 0, 0)
        val frame = requireNotNull(TelemetryFrame.parse(rawFrame))
        val ride = ActiveRide.started(0L, 0L, frame).add(frame, 1_000L).toRide()
        assertEquals(25.0, requireNotNull(frame.instantaneousMileageKilometresPerLitre), 0.000_001)
        assertEquals(0.0004, requireNotNull(ride.estimatedFuelLitres), 0.000_001)
        assertEquals(25.0, requireNotNull(ride.averageMileageKilometresPerLitre), 0.000_001)
    }

    @Test fun `fuel integration weights the endpoint consumption by speed`() {
        assertEquals(0.75, requireNotNull(fuelDeltaLitres(10.0, 10.0, 20.0, 36.0, 36.0)), 0.000_001)
        assertEquals(0.6, requireNotNull(fuelDeltaLitres(10.0, 10.0, 20.0, 20.0, 80.0)), 0.000_001)
    }

    /**
     * The bike encodes 0 km/L on every closed-throttle overrun, which parses to "no reading".
     * Voiding the ride on that threw the estimate away on every real ride, so an unmeasured
     * interval now contributes nothing instead.
     */
    @Test fun `a missing mileage interval contributes nothing but keeps the fuel estimate`() {
        val withGap = ActiveRide.started(0L, 0L, frame())
            .add(frame(), 1_000L)
            .add(frame(mileage = null), 2_000L)
            .add(frame(), 3_000L)
            .add(frame(), 4_000L)
            .toRide()
        val unbroken = ActiveRide.started(0L, 0L, frame())
            .add(frame(), 1_000L)
            .add(frame(), 2_000L)
            .add(frame(), 3_000L)
            .add(frame(), 4_000L)
            .toRide()
        val gapFuel = requireNotNull(withGap.estimatedFuelLitres)
        val fullFuel = requireNotNull(unbroken.estimatedFuelLitres)
        assertNotNull(withGap.averageMileageKilometresPerLitre)
        // Two of the four intervals had no reading on one endpoint, so the total is lower.
        assertTrue(gapFuel > 0.0 && gapFuel < fullFuel)
    }

    @Test fun `averages follow elapsed time instead of the number of notifications`() {
        val ride = ActiveRide.started(10_000L, 0L, frame(speed = 0.0, rpm = 1_000, throttle = 0))
            .add(frame(speed = 36.0, rpm = 3_000, throttle = 20), 1_000L)
            .add(frame(speed = 36.0, rpm = 3_000, throttle = 20), 3_000L)
            .toRide()
        assertEquals(30.0, ride.averageSpeedKph, 0.000_001)
        assertEquals(8_000.0 / 3, ride.averageRpm, 0.000_001)
        assertEquals(50.0 / 3, ride.averageThrottlePercent, 0.000_001)
        assertEquals(3_000L, ride.telemetryDurationMillis)
        assertEquals(13_000L, ride.endedAtMillis)
    }

    @Test fun `reconnect gaps affect elapsed ride time but not measured averages`() {
        val ride = ActiveRide.started(10_000L, 0L, frame())
            .add(frame(), 1_000L)
            .copy(lastSampleAtElapsedRealtime = 11_000L)
            .add(frame(speed = 72.0), 11_000L)
            .add(frame(speed = 72.0), 12_000L)
            .toRide()
        assertEquals(0.03, ride.distanceKilometres, 0.000_001)
        assertEquals(54.0, ride.averageSpeedKph, 0.000_001)
        assertEquals(2_000L, ride.telemetryDurationMillis)
        assertEquals(12_000L, ride.durationMillis)
    }

    private fun frame(mileage: Double? = 25.0, speed: Double = 36.0, rpm: Long = 3_000, throttle: Int = 10) =
        TelemetryFrame(speed, throttle, mileage, rpm)
}
