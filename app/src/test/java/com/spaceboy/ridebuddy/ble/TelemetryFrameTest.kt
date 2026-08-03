package com.spaceboy.ridebuddy.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TelemetryFrameTest {
    @Test
    fun `parses a valid little-endian telemetry frame`() {
        val payload = byteArrayOf(
            0x10,
            0x20,
            0x1C,
            38,
            29,
            0x2C,
            0x15,
            0x00,
            0x00,
            0x23,
        )

        val frame = requireNotNull(TelemetryFrame.parse(payload))

        assertEquals(72.0, frame.speedKilometresPerHour, 0.001)
        assertEquals(38, frame.throttlePercent)
        assertEquals(5.8, frame.instantaneousConsumptionLitresPer100Km, 0.001)
        assertEquals(5_420L, frame.engineRpm)
    }

    @Test
    fun `rejects malformed framing`() {
        assertNull(TelemetryFrame.parse(ByteArray(9)))
        assertNull(TelemetryFrame.parse(ByteArray(10)))
    }
}
