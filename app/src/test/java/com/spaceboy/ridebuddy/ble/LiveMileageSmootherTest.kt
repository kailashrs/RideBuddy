package com.spaceboy.ridebuddy.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveMileageSmootherTest {
    @Test
    fun `smooths only available live mileage values`() {
        val smoother = LiveMileageSmoother()

        assertEquals(20.0, requireNotNull(smoother.smooth(frame(20.0)).instantaneousMileageKilometresPerLitre), 0.0)
        assertEquals(22.0, requireNotNull(smoother.smooth(frame(30.0)).instantaneousMileageKilometresPerLitre), 0.0)
        assertNull(smoother.smooth(frame(null)).instantaneousMileageKilometresPerLitre)
    }

    @Test
    fun `reset starts a new live filter session`() {
        val smoother = LiveMileageSmoother()
        smoother.smooth(frame(20.0))
        smoother.reset()

        assertEquals(30.0, requireNotNull(smoother.smooth(frame(30.0)).instantaneousMileageKilometresPerLitre), 0.0)
    }

    private fun frame(mileage: Double?) = TelemetryFrame(
        speedKilometresPerHour = 50.0,
        throttlePercent = 20,
        instantaneousMileageKilometresPerLitre = mileage,
        engineRpm = 4_000,
    )
}
