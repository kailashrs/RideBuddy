package com.spaceboy.ridebuddy.core.tft

import android.os.SystemClock
import com.spaceboy.ridebuddy.ble.BleCharacteristics
import com.spaceboy.ridebuddy.domain.BikeConnection
import com.spaceboy.ridebuddy.domain.BikeConnectionState
import com.spaceboy.ridebuddy.domain.BikeWrite
import com.spaceboy.ridebuddy.domain.BikeWriteMode
import java.util.UUID
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

/** Which cluster surface a stationary run exercises. They are validated separately. */
enum class StationaryTftSurface { Navigation, Calls }

/** Runs a bounded, stationary-only sample across one inferred cluster output. */
class StationaryTftValidator(
    private val connection: BikeConnection,
    private val pauseBetweenWrites: suspend () -> Unit = { delay(200.milliseconds) },
    private val elapsedRealtimeMillis: () -> Long = SystemClock::elapsedRealtime,
) {
    suspend fun run(
        surface: StationaryTftSurface = StationaryTftSurface.Navigation,
        nowMillis: Long = System.currentTimeMillis(),
    ): StationaryTftTestResult {
        val frames = when (surface) {
            StationaryTftSurface.Navigation -> navigationFrames(nowMillis)
            StationaryTftSurface.Calls -> callFrames()
        }
        frames.forEachIndexed { index, frame ->
            safetyStopReason()?.let { reason ->
                return StationaryTftTestResult.SafetyStopped(reason, index)
            }
            if (!connection.writeAndAwait(BikeWrite(frame.characteristic, frame.payload, frame.mode))) {
                return StationaryTftTestResult.Failed(frame.characteristic, index)
            }
            if (index != frames.lastIndex) pauseBetweenWrites()
        }
        return StationaryTftTestResult.Succeeded(frames.size)
    }

    private fun navigationFrames(nowMillis: Long): List<Frame> {
        return buildList {
            add(Frame(BleCharacteristics.NavigationSession, TftPacketEncoder.session(RouteReady)))
            add(Frame(BleCharacteristics.NavigationStatus, TftPacketEncoder.status(NavigationActive)))
            add(Frame(BleCharacteristics.NavigationSession, TftPacketEncoder.session(GuidanceActive)))
            add(
                Frame(
                    BleCharacteristics.NavigationManeuver,
                    TftPacketEncoder.maneuver(current = 3, next = 0, roundaboutExit = 0, distanceMetres = 120),
                    BikeWriteMode.NoResponsePreferred,
                ),
            )
            add(
                Frame(
                    BleCharacteristics.NavigationTrip,
                    TftPacketEncoder.trip(
                        nowMillis + 10 * 60_000L,
                        destinationDistanceMetres = 2_000,
                        maneuverDistanceMetres = 120
                    ),
                    BikeWriteMode.NoResponsePreferred,
                ),
            )
            TftPacketEncoder.guidanceTextRows("STATIONARY TEST", "Turn right onto")
                .forEach { add(Frame(BleCharacteristics.NavigationText, it)) }
            add(
                Frame(
                    BleCharacteristics.NavigationSpeedLimit,
                    TftPacketEncoder.speedLimit(60),
                    BikeWriteMode.NoResponsePreferred,
                ),
            )
            add(Frame(BleCharacteristics.NavigationClear, TftPacketEncoder.clear()))
            add(Frame(BleCharacteristics.NavigationStatus, TftPacketEncoder.status(0)))
        }
    }

    /**
     * Walks the caller display through every state the cluster is ever told about, ending on
     * [TftCallEncoder.ended] so no invented call is left showing.
     *
     * The test number is deliberately longer than the cluster's ten-character field: if the
     * display shows the trailing ten digits, the OEM truncation rule is being applied correctly.
     * All three call characteristics are acknowledged writes, so every step here is confirmed by
     * the peer before the next one is sent.
     */
    private fun callFrames(): List<Frame> = listOf(
        Frame(BleCharacteristics.CallerName, TftCallEncoder.callerName(TestCallerName)),
        Frame(BleCharacteristics.CallerNumber, TftCallEncoder.callerNumber(TestCallerNumber)),
        Frame(BleCharacteristics.CallState, TftCallEncoder.ringing()),
        Frame(BleCharacteristics.CallState, TftCallEncoder.accepted()),
        Frame(BleCharacteristics.CallState, TftCallEncoder.ended()),
        Frame(BleCharacteristics.CallState, TftCallEncoder.outgoing()),
        Frame(BleCharacteristics.CallState, TftCallEncoder.ended()),
    )

    private fun safetyStopReason(): StationaryTftSafetyReason? {
        if (connection.connectionState.value !is BikeConnectionState.Connected) {
            return StationaryTftSafetyReason.Disconnected
        }
        if (!connection.diagnostics.value.authenticated) {
            return StationaryTftSafetyReason.NotAuthenticated
        }
        val reading = connection.latestTelemetryReading.value
            ?: return StationaryTftSafetyReason.TelemetryUnavailable
        val readingAgeMillis = elapsedRealtimeMillis() - reading.receivedAtElapsedRealtime
        if (readingAgeMillis !in 0..MaxTelemetryAgeMillis) {
            return StationaryTftSafetyReason.TelemetryStale
        }
        if (reading.frame.speedKilometresPerHour > MaxStationarySpeedKph) {
            return StationaryTftSafetyReason.BikeMoving
        }
        return null
    }

    private data class Frame(
        val characteristic: UUID,
        val payload: ByteArray,
        val mode: BikeWriteMode = BikeWriteMode.Default,
    )

    private companion object {
        const val RouteReady = 83
        const val GuidanceActive = 87
        const val NavigationActive = 132
        const val MaxStationarySpeedKph = 0.5
        const val MaxTelemetryAgeMillis = 2_000L
        const val TestCallerName = "TEST CALLER"

        /** Thirteen characters, so the cluster should show the last ten: 9876543210. */
        const val TestCallerNumber = "+919876543210"
    }
}

enum class StationaryTftSafetyReason {
    Disconnected,
    NotAuthenticated,
    TelemetryUnavailable,
    TelemetryStale,
    BikeMoving,
}

sealed interface StationaryTftTestResult {
    data class Succeeded(val acceptedWrites: Int) : StationaryTftTestResult
    data class Failed(val characteristic: UUID, val completedWrites: Int) : StationaryTftTestResult
    data class SafetyStopped(
        val reason: StationaryTftSafetyReason,
        val completedWrites: Int,
    ) : StationaryTftTestResult
}
