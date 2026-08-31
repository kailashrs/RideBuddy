package com.spaceboy.ridebuddy.data

import com.spaceboy.ridebuddy.ble.TelemetryFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun `decodes bike mileage and integrates litres by distance`() {
        val rawFrame = byteArrayOf(0x10, 0, 0, 10, 125, 0x10, 0x27, 0, 0)
        val frame = requireNotNull(TelemetryFrame.parse(rawFrame))
        val completed = ActiveRide.started(0L, 0L, frame)
            .add(frame, receivedAtElapsedRealtime = 1_000L, distanceDelta = 10.0)
            .toRide(2_000L)

        assertEquals(25.0, requireNotNull(frame.instantaneousMileageKilometresPerLitre), 0.000_001)
        assertEquals(0.4, requireNotNull(completed.estimatedFuelLitres), 0.000_001)
        assertEquals(25.0, requireNotNull(completed.averageMileageKilometresPerLitre), 0.000_001)
    }

    @Test
    fun `integrates the inverse mileage rate between samples`() {
        assertEquals(0.75, requireNotNull(fuelDeltaLitres(10.0, 10.0, 20.0)), 0.000_001)
    }

    @Test
    fun `does not turn unavailable mileage into zero fuel use`() {
        assertNull(fuelDeltaLitres(10.0, null, 20.0))
        assertNull(fuelDeltaLitres(10.0, 20.0, 0.0))

        val unavailable = ActiveRide.started(0L, 0L, frame(mileage = null))
            .add(frame(mileage = null), receivedAtElapsedRealtime = 1_000L, distanceDelta = 10.0)
            .toRide(2_000L)
        assertNull(unavailable.estimatedFuelLitres)
        assertNull(unavailable.averageMileageKilometresPerLitre)
    }

    private fun frame(mileage: Double?) = TelemetryFrame(
        speedKilometresPerHour = 36.0,
        throttlePercent = 10,
        instantaneousMileageKilometresPerLitre = mileage,
        engineRpm = 3_000,
    )

    @Test
    fun `a rebased baseline contributes no distance, which is how a reconnect is absorbed`() {
        // The first frame after a reconnect rebases lastSampleAtElapsedRealtime onto now, so the
        // interval is zero and the unmeasured gap adds nothing rather than being interpolated.
        assertEquals(0.0, distanceDeltaKilometres(90.0, 90.0, 0L), 0.0)
    }

    @Test
    fun `a gap longer than the integration window also contributes nothing`() {
        assertEquals(
            0.0,
            distanceDeltaKilometres(90.0, 90.0, MaxDistanceIntegrationGapMillis + 1),
            0.0,
        )
    }
}
