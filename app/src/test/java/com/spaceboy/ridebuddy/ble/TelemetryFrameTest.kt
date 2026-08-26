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
        assertEquals(5.8, requireNotNull(frame.instantaneousMileageKilometresPerLitre), 0.001)
        assertEquals(5_420L, frame.engineRpm)
    }

    @Test
    fun `accepts the nine bytes consumed by the OEM parser`() {
        val payload = byteArrayOf(0x10, 0x64, 0x00, 10, 20, 0x10, 0x27, 0x00, 0x00)

        val frame = requireNotNull(TelemetryFrame.parse(payload))

        assertEquals(1.0, frame.speedKilometresPerHour, 0.001)
        assertEquals(10_000L, frame.engineRpm)
    }

    @Test
    fun `ignores trailing bytes not consumed by the OEM parser`() {
        val payload = byteArrayOf(0x10, 0x64, 0x00, 10, 20, 0x10, 0x27, 0x00, 0x00, 0x7F, 0x55)

        assertEquals(1.0, requireNotNull(TelemetryFrame.parse(payload)).speedKilometresPerHour, 0.001)
    }

    @Test
    fun `treats zero mileage as unavailable`() {
        val payload = byteArrayOf(0x10, 0x64, 0x00, 10, 0, 0x10, 0x27, 0x00, 0x00)

        assertNull(requireNotNull(TelemetryFrame.parse(payload)).instantaneousMileageKilometresPerLitre)
    }

    @Test
    fun `rejects a short frame or wrong header`() {
        assertNull(TelemetryFrame.parse(ByteArray(8)))
        assertNull(TelemetryFrame.parse(ByteArray(9)))
    }
}
