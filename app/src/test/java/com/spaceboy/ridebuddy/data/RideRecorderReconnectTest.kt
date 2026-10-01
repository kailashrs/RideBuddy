package com.spaceboy.ridebuddy.data

import androidx.room.Room
import com.spaceboy.ridebuddy.InMemoryDataStore
import com.spaceboy.ridebuddy.data.db.RideBuddyDatabase
import com.spaceboy.ridebuddy.data.db.RawTelemetryDatabase
import com.spaceboy.ridebuddy.domain.BikeConnectionTarget
import com.spaceboy.ridebuddy.domain.TelemetryFrame
import com.spaceboy.ridebuddy.core.location.RideLocationLabeler
import com.spaceboy.ridebuddy.core.location.RideLocationTracker
import com.spaceboy.ridebuddy.domain.BikeConnection
import com.spaceboy.ridebuddy.domain.BikeConnectionState
import com.spaceboy.ridebuddy.domain.BikeControlEvent
import com.spaceboy.ridebuddy.domain.BikeIdentity
import com.spaceboy.ridebuddy.domain.BikeWrite
import com.spaceboy.ridebuddy.domain.BleDiagnostics
import com.spaceboy.ridebuddy.domain.TelemetryReading
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RideRecorderReconnectTest {
    @Test fun recentGraphSurvivesReconnectWithAnUnmeasuredGapAndClearsOnTerminalLoss() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val bike = RecordingBikeConnection()
        val repository = RideRepository(
            Room.inMemoryDatabaseBuilder(context, RideBuddyDatabase::class.java).build(),
            Room.inMemoryDatabaseBuilder(context, RawTelemetryDatabase::class.java).build(),
            scope,
        )
        val recorder = RideRecorder(bike, repository,
            scope, RideLocationTracker(context), AppSettingsRepository(InMemoryDataStore(AppSettings()), scope),
            RideLocationLabeler(context))
        try {
            recorder.start()
            withTimeout(3_000) { bike.raw.subscriptionCount.first { it > 0 } }
            bike.connectionState.value = BikeConnectionState.Connected("Test bike", null)
            // The first frames open an active ride and populate the sheet's recent graph.
            for (second in 1L..4L) {
                bike.raw.emit(reading(second * 1_000L, 30.0 + second))
                await { recorder.liveSamples.value.size >= second.toInt() }
            }
            assertNotNull(recorder.activeRide.value)
            val before = recorder.liveSamples.value

            bike.connectionState.value = BikeConnectionState.Connecting("Test bike", 1, 3)
            await { bike.connectionState.value is BikeConnectionState.Connecting }
            delay(50) // Let the recorder's connection-state collector process the transition.
            assertEquals(before, recorder.liveSamples.value)
            bike.connectionState.value = BikeConnectionState.Authenticating("Test bike")
            delay(50)
            assertEquals(before, recorder.liveSamples.value)

            bike.connectionState.value = BikeConnectionState.Connected("Test bike", null)
            bike.raw.emit(reading(12_000L, 42.0))
            await { recorder.liveSamples.value.size == before.size + 1 }
            val after = recorder.liveSamples.value
            assertEquals(before, after.dropLast(1))
            assertEquals(0.0, after.last().accelerationMetresPerSecondSquared, 0.0)
            val chart = telemetryChartData(after, 120) { it.speedKph }
            assertTrue("The outage should be a blank gap on the graph", null in chart.values)
            assertTrue("Ride recording continues across reconnect", recorder.activeRide.value!!.distanceKilometres > 0.0)

            bike.connectionState.value = BikeConnectionState.Failed("Link lost", retriesExhausted = true)
            await { recorder.activeRide.value == null && recorder.liveSamples.value.isEmpty() }
        } finally {
            scope.cancel()
        }
    }

    @Test fun accelerationIgnoresWheelSpeedStepJitterButKeepsASustainedLaunch() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val bike = RecordingBikeConnection()
        val repository = RideRepository(
            Room.inMemoryDatabaseBuilder(context, RideBuddyDatabase::class.java).build(),
            Room.inMemoryDatabaseBuilder(context, RawTelemetryDatabase::class.java).build(),
            scope,
        )
        val recorder = RideRecorder(bike, repository,
            scope, RideLocationTracker(context), AppSettingsRepository(InMemoryDataStore(AppSettings()), scope),
            RideLocationLabeler(context))
        val seen = java.util.Collections.synchronizedList(mutableListOf<Double>())
        try {
            recorder.start()
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                recorder.liveSampleEvents.collect { seen += it.accelerationMetresPerSecondSquared }
            }
            withTimeout(3_000) { bike.raw.subscriptionCount.first { it > 0 } }
            bike.connectionState.value = BikeConnectionState.Connected("Test bike", null)
            suspend fun frame(elapsed: Long, speed: Double) {
                val count = seen.size
                bike.raw.emit(reading(elapsed, speed))
                await { seen.size > count }
            }

            // Steady 30 km/h reported as eight 0.377 km/h steps up and back every frame, as the
            // wheel sensor does: ±3.4 m/s² frame to frame, though the bike is not accelerating.
            for (index in 0..11) frame(index * 250L, 30.0 + if (index % 2 == 0) 0.0 else 8 * 0.377)
            assertTrue(seen.drop(4).all { kotlin.math.abs(it) < 3.0 })

            // A real 4 m/s² launch, 3.6 km/h every 250 ms, still reads as one.
            seen.clear()
            for (index in 0..7) frame(10_000L + index * 250L, 10.0 + index * 3.6)
            assertEquals(4.0, seen.last(), 0.01)
        } finally {
            scope.cancel()
        }
    }

    private suspend fun await(condition: () -> Boolean) = withTimeout(3_000) {
        while (!condition()) delay(10)
    }

    private fun reading(elapsed: Long, speed: Double) = TelemetryReading(
        TelemetryFrame(speed, 20, null, 4_000L),
        receivedAtMillis = 1_000_000L + elapsed,
        receivedAtElapsedRealtime = elapsed,
    )
}

private class RecordingBikeConnection : BikeConnection {
    override val connectionState = MutableStateFlow<BikeConnectionState>(BikeConnectionState.Disconnected)
    val raw = MutableSharedFlow<TelemetryReading>(extraBufferCapacity = 16)
    override val readings: SharedFlow<TelemetryReading> = raw
    override val latestReading: StateFlow<TelemetryReading?> = MutableStateFlow(null)
    override val identity: StateFlow<BikeIdentity> = MutableStateFlow(BikeIdentity())
    override val diagnostics: StateFlow<BleDiagnostics> = MutableStateFlow(BleDiagnostics())
    override val controls: SharedFlow<BikeControlEvent> = MutableSharedFlow()
    override fun connect(target: BikeConnectionTarget) = Unit
    override fun disconnect() = Unit
    override fun enqueueWrite(characteristic: UUID, payload: ByteArray) = Unit
    override suspend fun writeAndAwait(write: BikeWrite) = true
}
