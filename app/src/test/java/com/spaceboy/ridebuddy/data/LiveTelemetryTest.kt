package com.spaceboy.ridebuddy.data

import com.spaceboy.ridebuddy.domain.TelemetryFrame
import com.spaceboy.ridebuddy.domain.TelemetryReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveTelemetryTest {
    private fun reading(mileage: Double?) = TelemetryReading(TelemetryFrame(40.0, 20, mileage, 5_000), 0, 0)

    @Test
    fun `the first sample seeds the average and later ones move it a fifth of the way`() {
        val seeded = LiveTelemetry().next(reading(30.0))
        assertEquals(30.0, seeded.frame!!.instantaneousMileageKilometresPerLitre!!, 1e-9)

        assertEquals(28.0, seeded.next(reading(20.0)).frame!!.instantaneousMileageKilometresPerLitre!!, 1e-9)
    }

    @Test
    fun `a missing reading shows as missing but keeps the average`() {
        val seeded = LiveTelemetry().next(reading(30.0))

        val gap = seeded.next(reading(null))
        assertNull(gap.frame!!.instantaneousMileageKilometresPerLitre)
        assertEquals(28.0, gap.next(reading(20.0)).frame!!.instantaneousMileageKilometresPerLitre!!, 1e-9)
    }

    @Test
    fun `losing the link clears the frame and the average`() {
        assertEquals(LiveTelemetry(), LiveTelemetry().next(reading(30.0)).next(null))
    }
}
