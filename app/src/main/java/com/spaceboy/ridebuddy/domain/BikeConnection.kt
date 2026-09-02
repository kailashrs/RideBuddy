package com.spaceboy.ridebuddy.domain

import androidx.compose.runtime.Immutable
import com.spaceboy.ridebuddy.ble.TelemetryFrame
import com.spaceboy.ridebuddy.ble.BikeConnectionTarget
import com.spaceboy.ridebuddy.ble.BluetoothAddress
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharedFlow
import java.util.UUID

/** Selects the GATT acknowledgement behavior a packet family needs. */
enum class BikeWriteMode {
    /** Acknowledged. Use where the outcome matters — state changes, protocol steps. */
    Default,

    /**
     * Unacknowledged where the characteristic supports it. Saves a round trip on
     * high-rate display fields, whose next update supersedes a dropped one anyway.
     */
    NoResponsePreferred,
}

/**
 * One value bound for one characteristic.
 *
 * `equals`/`hashCode` are written out because [payload] is an array, whose identity-based
 * defaults would make two writes of the same bytes compare unequal — and these values are
 * compared and used as map keys throughout the display bridge.
 */
data class BikeWrite(
    val characteristic: UUID,
    val payload: ByteArray,
    val mode: BikeWriteMode = BikeWriteMode.Default,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BikeWrite) return false
        return characteristic == other.characteristic &&
            mode == other.mode &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int = 31 * (31 * characteristic.hashCode() + mode.hashCode()) +
        payload.contentHashCode()
}

/**
 * A telemetry frame with both clocks attached.
 *
 * [receivedAtMillis] is wall-clock, for recording and display. [receivedAtElapsedRealtime]
 * is monotonic, and is the one to use for freshness: wall-clock time can jump backwards on
 * a time-zone or NTP correction, which would make a current reading look arbitrarily old
 * and trip a staleness check for reasons that have nothing to do with the bike.
 */
data class TelemetryReading(
    val frame: TelemetryFrame,
    val receivedAtMillis: Long,
    val receivedAtElapsedRealtime: Long,
)

/**
 * The app's whole view of the motorcycle link.
 *
 * An interface so everything above it — recording, display bridges, the UI — can be
 * exercised without a Bluetooth stack. The implementation is
 * [com.spaceboy.ridebuddy.ble.AndroidBikeConnection].
 */
interface BikeConnection {
    val connectionState: StateFlow<BikeConnectionState>
    /** Every valid frame, without StateFlow equality conflation. UI consumers should use [telemetry]. */
    val rawTelemetry: SharedFlow<TelemetryReading>
    val telemetry: StateFlow<TelemetryFrame?>
    /** Unthrottled frame and receipt times for freshness-sensitive decisions. */
    val latestTelemetryReading: StateFlow<TelemetryReading?>
    val identity: StateFlow<BikeIdentity>
    val diagnostics: StateFlow<BleDiagnostics>
    val controls: SharedFlow<BikeControlEvent>

    fun connect(target: BikeConnectionTarget)
    fun disconnect()

    /** Reports a failure that happened before the connection itself could be attempted. */
    fun notifyStartFailed(message: String = "Unable to start connection service") {}

    /** Fire-and-forget write. Silently dropped when there is no authenticated session. */
    fun enqueueWrite(characteristic: UUID, payload: ByteArray)

    /**
     * Queues a write with its required acknowledgement behavior and awaits its outcome.
     * Implementations return false when the write cannot be queued, is rejected, or times out.
     */
    suspend fun writeAndAwait(write: BikeWrite): Boolean
}

/** Where the link is. Drives both the UI and the decision to start another attempt. */
sealed interface BikeConnectionState {
    data object Disconnected : BikeConnectionState
    /**
     * GATT transport is being established. The optional [reconnectAttempt] / [maxAttempts]
     * pair is non-null only on the exponential-backoff retry path, and is shown on the
     * diagnostics screen alone: a rider cannot act differently on attempt four than on attempt
     * two, so the rider-facing surfaces say only that a connection is in progress.
     */
    data class Connecting(
        val deviceName: String?,
        val reconnectAttempt: Int? = null,
        val maxAttempts: Int? = null,
    ) : BikeConnectionState
    /** Transport is up; the protection handshake and subscription set are in progress. */
    data class Authenticating(val deviceName: String) : BikeConnectionState

    /** Fully up and verified. [rssi] fills in once the first signal-strength poll returns. */
    data class Connected(val deviceName: String, val rssi: Int?) : BikeConnectionState
    /**
     * The stack is not connected and will not retry on its own.
     *
     * [retriesExhausted] marks the terminal end of the bounded backoff schedule. Only a fresh
     * BLE appearance or an explicit user retry may start another attempt from that state; an
     * app relaunch must not silently reset the retry budget.
     */
    data class Failed(
        val message: String,
        val retriesExhausted: Boolean = false,
    ) : BikeConnectionState
}

/**
 * What is known about the paired motorcycle. Every field is nullable and filled in
 * opportunistically, from indications the cluster sends on its own schedule — which for the
 * software version can be minutes into a session. Nothing here is ever read.
 */
data class BikeIdentity(
    val vin: String? = null,
    val clusterSoftwareVersion: String? = null,
    val lastConnectedAtMillis: Long? = null,
)

/** How far the protection handshake has got. Surfaced on the diagnostics screen. */
enum class ProtectionPhase {
    Idle,
    SubscribingChallenge,
    AwaitingChallenge,
    Responding,

    /** Handshake done; waiting for the subscription set to prove the session is live. */
    Verifying,

    Ready,
}

/** Which route through the handshake a session took. */
enum class ProtectionPath {
    /** The challenge was skipped because this bike had already accepted one. */
    StoredAcceptance,

    /** A challenge was received and answered on this connection. */
    ChallengeIndication,
}

/**
 * Everything the diagnostics screen shows about the link, in one snapshot.
 *
 * `@Immutable` because it is republished on every telemetry frame; without it Compose
 * would treat each new instance as unconditionally changed and recompose everything
 * reading any part of it.
 */
@Immutable
data class BleDiagnostics(
    val authenticated: Boolean = false,
    val protectionPhase: ProtectionPhase = ProtectionPhase.Idle,
    val protectionPath: ProtectionPath? = null,
    val bonded: Boolean? = null,
    val attMtu: Int? = null,
    val servicesDiscovered: Int = 0,
    val notificationsReceived: Long = 0,
    val writesCompleted: Long = 0,
    val descriptorWritesCompleted: Long = 0,
    val activeGattOperation: String? = null,
    val malformedTelemetryFrames: Long = 0,
    val droppedRawTelemetryFrames: Long = 0,
    val lastFrameAtMillis: Long? = null,
    val rssi: Int? = null,
    val telemetryHz: Double = 0.0,
    val serviceSnapshot: List<String> = emptyList(),
    val recentFrames: List<String> = emptyList(),
    val recentEvents: List<String> = emptyList(),
    /** The real failure, retained across automatic reattempts against the same target. */
    val lastFailure: ConnectionFailure? = null,
    /** What the last authenticated link negotiated; teardown does not clear it. */
    val lastSuccessfulLink: LinkSnapshot? = null,
    /** Context of the attempt currently in flight. */
    val attempt: ConnectionAttemptContext = ConnectionAttemptContext(),
    /** Set when the stack deliberately declined to start or continue automatic attempts. */
    val suppressionReason: String? = null,
) {
    val lastError: String? get() = lastFailure?.message
    val lastErrorAtMillis: Long? get() = lastFailure?.atMillis
}

/** Something the cluster initiated: a handlebar press, or a statement about its own state. */
sealed interface BikeControlEvent {
    /**
     * The rider pressed the handlebar control while the cluster showed **GO**.
     *
     * One physical button produces all three navigation events. What separates them is
     * which screen the cluster is on: **GO** is drawn only while a route is staged, and
     * [SkipManeuver] and [ExitNavigation] only while guidance is running.
     */
    data object StartNavigation : BikeControlEvent

    /** Drop the current waypoint and advance to the next. Guidance screen only. */
    data object SkipManeuver : BikeControlEvent

    /** End navigation. Guidance screen only. */
    data object ExitNavigation : BikeControlEvent

    /** Answer (1) or reject/end (0) from the handlebar, while a call is up. */
    data class CallAction(val code: Int) : BikeControlEvent

    /**
     * The cluster is asserting that a call is live on its side.
     *
     * Nothing is written back in response. What matters is that it, like the first
     * telemetry frame, marks the cluster as ready to receive call writes — until one of
     * those arrives, a handlebar press has nothing to act on.
     */
    data object ClusterCallActive : BikeControlEvent

    /**
     * The cluster has restarted and wants the phone's state again.
     *
     * Answered by rewriting the current call state and clearing the notification icons.
     * This arrives without a BLE reconnect, so it is a different signal from
     * [BikeConnectionState.Connected] — the link never dropped, but the display forgot
     * everything it was showing.
     */
    data object ClusterReady : BikeControlEvent
}
