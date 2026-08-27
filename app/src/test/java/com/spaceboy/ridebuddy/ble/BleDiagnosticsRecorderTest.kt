package com.spaceboy.ridebuddy.ble

import com.spaceboy.ridebuddy.domain.BleDiagnostics
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Test

class BleDiagnosticsRecorderTest {
    @Test
    fun `a telemetry frame publishes one snapshot carrying the frame, rate and validity`() {
        val recorder = BleDiagnosticsRecorder()

        val published = recorder.snapshotsPublishedBy {
            recorder.recordTelemetryNotification(
                frameLine = "8410 10 00",
                receivedAtMillis = 1_000L,
                telemetryHz = 4.0,
                droppedRawTelemetryFrames = 2L,
                malformed = true,
            )
        }

        // Telemetry arrives continuously, so a single frame must not fan out into several
        // republished snapshots: every collector, including the Live screen, pays for each one.
        assertEquals(1, published.size)
        val latest = recorder.value
        assertEquals(1L, latest.notificationsReceived)
        assertEquals(4.0, latest.telemetryHz, 0.0)
        assertEquals(2L, latest.droppedRawTelemetryFrames)
        assertEquals(1L, latest.malformedTelemetryFrames)
        assertEquals(listOf("8410 10 00"), latest.recentFrames)
    }

    @Test
    fun `valid frames leave the malformed counter alone`() {
        val recorder = BleDiagnosticsRecorder()

        repeat(3) {
            recorder.recordTelemetryNotification("8410", 1L, 4.0, 0L, malformed = false)
        }

        assertEquals(3L, recorder.value.notificationsReceived)
        assertEquals(0L, recorder.value.malformedTelemetryFrames)
    }

    @Test
    fun `the recent frame ring is bounded`() {
        val recorder = BleDiagnosticsRecorder()

        repeat(50) { index -> recorder.recordNotification("frame $index", index.toLong()) }

        assertEquals(30, recorder.value.recentFrames.size)
        assertEquals("frame 49", recorder.value.recentFrames.first())
        assertEquals(50L, recorder.value.notificationsReceived)
    }

    @Test
    fun `teardown keeps the recorded failure and the last successful link`() {
        val recorder = BleDiagnosticsRecorder()
        recorder.markAuthenticated(path = null)
        recorder.setAttMtu(185)
        recorder.setServices(serviceCount = 3, characteristicLabels = listOf("8410"))

        recorder.resetForTeardown(sessionId = 7L, establishedAtMillis = 100L, durationMillis = 5_000L)

        val latest = recorder.value
        assertEquals(false, latest.authenticated)
        assertEquals(7L, latest.lastSuccessfulLink?.sessionId)
        assertEquals(185, latest.lastSuccessfulLink?.attMtu)
        assertEquals(3, latest.lastSuccessfulLink?.servicesDiscovered)
    }

    /** Unconfined collection makes every distinct published snapshot observable in order. */
    private fun BleDiagnosticsRecorder.snapshotsPublishedBy(block: () -> Unit): List<BleDiagnostics> {
        val snapshots = CopyOnWriteArrayList<BleDiagnostics>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        scope.launch { diagnostics.collect(snapshots::add) }
        val alreadyReplayed = snapshots.size
        return try {
            block()
            snapshots.drop(alreadyReplayed)
        } finally {
            scope.cancel()
        }
    }
}
