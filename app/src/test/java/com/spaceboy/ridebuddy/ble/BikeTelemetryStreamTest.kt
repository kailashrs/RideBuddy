package com.spaceboy.ridebuddy.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BikeTelemetryStreamTest {
    @Test
    fun `publishes valid readings and rejects malformed payloads`() {
        val stream = BikeTelemetryStream()
        var elapsedRealtimeCalls = 0
        val malformed = stream.accept(ByteArray(8), 1_000L) {
            elapsedRealtimeCalls++
            2_000L
        }

        assertFalse(malformed.valid)
        assertEquals(0, elapsedRealtimeCalls)
        assertNull(stream.latestReading.value)

        val valid = stream.accept(validTelemetryPayload(), 1_100L) {
            elapsedRealtimeCalls++
            2_100L
        }

        assertTrue(valid.valid)
        assertEquals(1, elapsedRealtimeCalls)
        assertEquals(1_100L, stream.latestReading.value?.receivedAtMillis)
        assertEquals(2_100L, stream.latestReading.value?.receivedAtElapsedRealtime)
        // The OEM-rate diagnostic counts every telemetry notification, including malformed ones.
        assertEquals(0.4, valid.telemetryHz, 0.0001)
    }

    @Test
    fun `reset clears freshness state and telemetry rate window`() {
        val stream = BikeTelemetryStream()
        stream.accept(validTelemetryPayload(), 10_000L) { 20_000L }

        stream.reset()
        val next = stream.accept(validTelemetryPayload(), 11_000L) { 21_000L }

        assertEquals(0.2, next.telemetryHz, 0.0001)
        assertEquals(11_000L, stream.latestReading.value?.receivedAtMillis)
    }

    @Test
    fun `reports no dropped frames while the consumer keeps up`() {
        val stream = BikeTelemetryStream()

        val first = stream.accept(validTelemetryPayload(), 1_000L) { 2_000L }
        val second = stream.accept(validTelemetryPayload(), 1_250L) { 2_250L }

        assertEquals(0L, first.droppedRawTelemetryFrames)
        assertEquals(0L, second.droppedRawTelemetryFrames)
    }

    @Test
    fun `a malformed frame reports the running drop count rather than resetting it`() {
        val stream = BikeTelemetryStream()
        stream.accept(validTelemetryPayload(), 1_000L) { 2_000L }

        val malformed = stream.accept(ByteArray(8), 1_250L) { 2_250L }

        assertFalse(malformed.valid)
        assertEquals(0L, malformed.droppedRawTelemetryFrames)
    }

    private fun validTelemetryPayload(): ByteArray = byteArrayOf(
        0x10,
        0x20,
        0x1C,
        38,
        29,
        0x2C,
        0x15,
        0x00,
        0x00,
    )
}
