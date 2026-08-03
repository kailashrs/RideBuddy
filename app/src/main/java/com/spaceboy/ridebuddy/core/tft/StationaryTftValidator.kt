package com.spaceboy.ridebuddy.core.tft

import android.os.SystemClock
import com.spaceboy.ridebuddy.ble.BleCharacteristics
import com.spaceboy.ridebuddy.domain.BikeConnection
import com.spaceboy.ridebuddy.domain.BikeConnectionState
import com.spaceboy.ridebuddy.domain.BikeWrite
import com.spaceboy.ridebuddy.domain.BikeWriteMode
import java.util.UUID
import kotlinx.coroutines.delay

/** Runs a bounded, stationary-only sample across every inferred navigation output. */
class StationaryTftValidator(
    private val connection: BikeConnection,
    private val pauseBetweenWrites: suspend () -> Unit = { delay(200) },
    private val elapsedRealtimeMillis: () -> Long = SystemClock::elapsedRealtime,
) {
    suspend fun run(nowMillis: Long = System.currentTimeMillis()): StationaryTftTestResult {
        val frames = buildList {
            add(Frame(BleCharacteristics.NavigationSession, TftPacketEncoder.session(GuidanceStarted)))
            add(Frame(BleCharacteristics.NavigationStatus, TftPacketEncoder.status(NavigationActive)))
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
                    TftPacketEncoder.trip(nowMillis + 10 * 60_000L, destinationDistanceMetres = 2_000, maneuverDistanceMetres = 120),
                    BikeWriteMode.NoResponsePreferred,
                ),
            )
            TftPacketEncoder.displayTextRows("STATIONARY TFT TEST").forEach { add(Frame(BleCharacteristics.NavigationText, it)) }
            add(
                Frame(
                    BleCharacteristics.NavigationSpeedLimit,
                    TftPacketEncoder.speedLimit(60),
                    BikeWriteMode.NoResponsePreferred,
                ),
            )
            add(Frame(BleCharacteristics.NavigationSession, TftPacketEncoder.session(GuidanceEnded)))
            add(Frame(BleCharacteristics.NavigationClear, TftPacketEncoder.clear()))
            add(Frame(BleCharacteristics.NavigationStatus, TftPacketEncoder.status(0)))
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
        const val GuidanceStarted = 80
        const val GuidanceEnded = 87
        const val NavigationActive = 132
        const val MaxStationarySpeedKph = 0.5
        const val MaxTelemetryAgeMillis = 2_000L
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
