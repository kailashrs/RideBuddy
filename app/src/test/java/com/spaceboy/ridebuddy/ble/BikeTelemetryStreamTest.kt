package com.spaceboy.ridebuddy.ble

import org.junit.Assert.assertEquals
import org.junit.Test

class BikeTelemetryStreamTest {
    @Test
    fun `publishes each reading with both clocks`() {
        val stream = BikeTelemetryStream()

        stream.accept(validTelemetryPayload(), 1_100L) { 2_100L }

        assertEquals(1_100L, stream.latestReading.value?.receivedAtMillis)
        assertEquals(2_100L, stream.latestReading.value?.receivedAtElapsedRealtime)
    }

    @Test
    fun `reset clears freshness state and telemetry rate window`() {
        val stream = BikeTelemetryStream()
        stream.accept(validTelemetryPayload(), 10_000L) { 20_000L }

        stream.reset()
        val telemetryHz = stream.accept(validTelemetryPayload(), 11_000L) { 21_000L }

        assertEquals(0.2, telemetryHz, 0.0001)
        assertEquals(11_000L, stream.latestReading.value?.receivedAtMillis)
    }

    @Test
    fun `the rate window runs on the monotonic clock`() {
        val stream = BikeTelemetryStream()
        stream.accept(validTelemetryPayload(), 100_000L) { 10_000L }
        val telemetryHz = stream.accept(validTelemetryPayload(), 1_000L) { 16_000L }
        assertEquals(0.2, telemetryHz, 0.0001)
        assertEquals(16_000L, stream.latestReading.value?.receivedAtElapsedRealtime)
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
