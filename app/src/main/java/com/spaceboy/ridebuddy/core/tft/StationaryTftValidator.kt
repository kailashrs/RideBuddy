package com.spaceboy.ridebuddy.core.tft

import android.os.SystemClock
import com.google.android.libraries.mapsplatform.turnbyturn.model.Maneuver
import com.spaceboy.ridebuddy.ble.BleCharacteristics
import com.spaceboy.ridebuddy.domain.BikeConnection
import com.spaceboy.ridebuddy.domain.BikeConnectionState
import com.spaceboy.ridebuddy.domain.BikeWrite
import com.spaceboy.ridebuddy.domain.BikeWriteMode
import java.util.UUID
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

/**
 * The cluster surfaces a stationary run walks, in order.
 *
 * They are separate phases because the rider confirms each one before the next begins: a single
 * question covering both surfaces cannot say which of them was wrong, and by the time it is asked
 * the evidence for the first has already been cleared off the screen.
 */
enum class StationaryTftPhase { Navigation, Calls }

/**
 * Runs a bounded, stationary-only sample across one cluster output.
 *
 * Each phase ends by tidying up after itself — navigation clears, the call sequence ends on
 * [TftCallEncoder.ended] — so nothing invented is left showing between phases or after the last.
 */
class StationaryTftValidator(
    private val connection: BikeConnection,
    private val pauseBetweenWrites: suspend () -> Unit = { delay(200.milliseconds) },
    private val elapsedRealtimeMillis: () -> Long = SystemClock::elapsedRealtime,
) {
    /**
     * Writes one phase's frames in order, stopping at the first failure or safety violation.
     *
     * The safety check runs before *every* write, not once at the start: the run takes
     * several seconds, and the point is to abort the moment the bike starts moving rather
     * than to establish that it was parked when the rider pressed the button.
     */
    suspend fun run(
        phase: StationaryTftPhase,
        nowMillis: Long = System.currentTimeMillis(),
    ): StationaryTftTestResult {
        val frames = when (phase) {
            StationaryTftPhase.Navigation -> navigationFrames(nowMillis)
            StationaryTftPhase.Calls -> callFrames()
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

    /**
     * A complete guidance sequence: enter the session, draw one turn with distances, text
     * and a speed limit, then clear it all back off the display.
     *
     * The sequence mirrors what [TftNavigationBridge] transmits for a route started without a
     * preview: session [RouteRequested], status [NavigationActive], then session
     * [GuidanceActive]. A test that walked a different sequence would confirm the cluster
     * accepts frames the app never sends.
     *
     * The transitional [RouteRequested] is included deliberately. In production it is dirty
     * state, not a queued packet, so it reaches the cluster only when the worker drains between
     * the route request and the first guidance update — but that is a real production sequence,
     * and it is the one nothing else exercises. Emitting it here is what makes this phase a test
     * of the `80` to `87` transition rather than only of its coalesced outcome.
     *
     * Two absences are also deliberate: no preview `83`, because a direct start never passes
     * through it; and no status `0`, because that value is a sentinel that suppresses its own
     * write rather than being transmitted.
     *
     * Distances and times are chosen to be unmistakable to someone looking at the cluster —
     * a round 120 m, an ETA ten minutes out — so a mis-decoded field reads as obviously
     * wrong rather than plausible. The maneuver ids are real turns, in opposite directions,
     * so the arrow mapping is genuinely exercised: the rider is looking for a right arrow now
     * and a left one queued next, and either arrow being wrong is visible at a glance. This
     * is the part of the protocol least confirmed by capture, so a fallback pictogram here
     * would make the phase silent about exactly what it exists to check.
     */
    private fun navigationFrames(nowMillis: Long): List<Frame> {
        return buildList {
            add(Frame(BleCharacteristics.NavigationSession, TftPacketEncoder.session(RouteRequested)))
            add(Frame(BleCharacteristics.NavigationStatus, TftPacketEncoder.status(NavigationActive)))
            add(Frame(BleCharacteristics.NavigationSession, TftPacketEncoder.session(GuidanceActive)))
            add(
                Frame(
                    BleCharacteristics.NavigationManeuver,
                    TftPacketEncoder.maneuver(
                        current = Maneuver.TURN_RIGHT,
                        next = Maneuver.TURN_LEFT,
                        roundaboutExit = 0,
                        distanceMetres = 120,
                    ),
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
            // The clear packet does not touch the speed limit, so the 60 written above would
            // otherwise stay on the display after the test has finished.
            add(
                Frame(
                    BleCharacteristics.NavigationSpeedLimit,
                    TftPacketEncoder.speedLimit(0),
                    BikeWriteMode.NoResponsePreferred,
                ),
            )
        }
    }

    /**
     * Walks the caller display through every state the cluster is ever told about, ending on
     * [TftCallEncoder.ended] so no invented call is left showing.
     *
     * The test number is deliberately longer than the cluster's ten-character field: if the
     * display shows the trailing ten digits, the truncation rule in
     * [TftCallEncoder.callerNumber] is being applied correctly. All three call
     * characteristics are acknowledged writes, so every step here is confirmed by the peer
     * before the next one is sent.
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

    /**
     * Why the run must not continue, or null when it may.
     *
     * Telemetry age is checked as well as speed, because a stale reading of zero says
     * nothing about whether the bike is moving now. The negative-age branch catches a
     * reading stamped in the future, which means the clocks disagree and the age cannot be
     * trusted either way.
     */
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
        // Session and status values; see TftNavigationBridge for the full vocabulary.
        const val RouteRequested = 80
        const val GuidanceActive = 87
        const val NavigationActive = 132

        /** Above idle sensor noise, far below any speed a bike could actually be moving at. */
        const val MaxStationarySpeedKph = 0.5

        /** Older than this and the reading no longer describes the present. */
        const val MaxTelemetryAgeMillis = 2_000L

        const val TestCallerName = "TEST CALLER"

        /** Thirteen characters, so the cluster should show the last ten: 9876543210. */
        const val TestCallerNumber = "+919876543210"
    }
}

/** Why a run was refused or cut short. Each maps to its own message on the settings screen. */
enum class StationaryTftSafetyReason {
    Disconnected,
    NotAuthenticated,

    /** No telemetry at all, so movement cannot be ruled out. */
    TelemetryUnavailable,

    /** Telemetry too old to describe the present. */
    TelemetryStale,

    BikeMoving,
}

/**
 * Outcome of a run. All three carry a write count so the rider is told how far it got
 * rather than just that it stopped.
 */
sealed interface StationaryTftTestResult {
    data class Succeeded(val acceptedWrites: Int) : StationaryTftTestResult

    /** A write was not acknowledged. The characteristic names which display field failed. */
    data class Failed(val characteristic: UUID, val completedWrites: Int) : StationaryTftTestResult

    data class SafetyStopped(
        val reason: StationaryTftSafetyReason,
        val completedWrites: Int,
    ) : StationaryTftTestResult
}
