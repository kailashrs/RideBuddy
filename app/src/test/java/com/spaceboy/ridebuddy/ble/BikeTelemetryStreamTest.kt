package com.spaceboy.ridebuddy.ble

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BikeTelemetryStreamTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `publishes valid readings and rejects malformed payloads`() {
        val stream = BikeTelemetryStream(scope)
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
        val stream = BikeTelemetryStream(scope)
        stream.accept(validTelemetryPayload(), 10_000L) { 20_000L }

        stream.reset()
        val next = stream.accept(validTelemetryPayload(), 11_000L) { 21_000L }

        assertEquals(0.2, next.telemetryHz, 0.0001)
        assertEquals(11_000L, stream.latestReading.value?.receivedAtMillis)
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
