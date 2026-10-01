package com.spaceboy.ridebuddy.domain

import android.net.MacAddress
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
 * `equals`/`hashCode` compare [payload] by content; an array's defaults compare identity.
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
 * The app's whole view of the motorcycle link.
 *
 * An interface so everything above it — recording, the cluster display, the UI — can be
 * exercised without a Bluetooth stack. The implementation is
 * [com.spaceboy.ridebuddy.ble.AndroidBikeConnection].
 */
interface BikeConnection {
    val connectionState: StateFlow<BikeConnectionState>
    /** Every valid frame, without StateFlow conflation, for recording. */
    val readings: SharedFlow<TelemetryReading>

    /** The newest frame, or null when the link has none. */
    val latestReading: StateFlow<TelemetryReading?>
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

/**
 * A connection request for the associated motorcycle. [trigger] travels with it so diagnostics
 * can tell a rider's connect from one a presence callback asked for; automatic retries are owned
 * by the connection itself and never expressed as a new target.
 */
data class BikeConnectionTarget(
    val address: MacAddress,
    val deviceName: String,
    val trigger: ConnectionAttemptTrigger = ConnectionAttemptTrigger.UserRequest,
)

/** Where the link is. Drives both the UI and the decision to start another attempt. */
sealed interface BikeConnectionState {
    data object Disconnected : BikeConnectionState
    /**
     * GATT transport is being established. The optional [reconnectAttempt] / [maxAttempts]
     * pair counts the current attempt within the three-attempt cycle, including the first,
     * and is shown on the diagnostics screen alone.
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

/** Everything the diagnostics screen shows about the link, in one snapshot. */
data class BleDiagnostics(
    val authenticated: Boolean = false,
    val protectionPhase: ProtectionPhase = ProtectionPhase.Idle,
    val protectionPath: ProtectionPath? = null,
    val bonded: Boolean? = null,
    val attMtu: Int? = null,
    val servicesDiscovered: Int = 0,
    val notificationsReceived: Long = 0,
    val writesCompleted: Long = 0,
    val lastFrameAtMillis: Long? = null,
    val rssi: Int? = null,
    val telemetryHz: Double = 0.0,
    val serviceSnapshot: List<String> = emptyList(),
    val recentFrames: List<String> = emptyList(),
    val recentEvents: List<String> = emptyList(),
    /** The real failure, kept across automatic reattempts so it is still there to read. */
    val lastError: String? = null,
    val lastErrorAtMillis: Long? = null,
    /** Why automatic attempts stopped, when they did. */
    val suppressionReason: String? = null,
)

/** Why a connection attempt was started, so diagnostics never conflate the retry paths. */
enum class ConnectionAttemptTrigger {
    /** The rider asked for this connection, directly or through onboarding. */
    UserRequest,

    /** A companion BLE_APPEARED edge. */
    PresenceAppearance,

    /** The one automatic attempt an app launch is allowed to make. */
    AppLaunch,
}

/** Something the cluster initiated: a handlebar press, or a statement about its own state. */
sealed interface BikeControlEvent {
    /**
     * The rider pressed the handlebar control while the cluster showed **GO**.
     *
     * One physical button produces both navigation events. What separates them is which
     * screen the cluster is on: **GO** is drawn only while a route is staged, and
     * [ExitNavigation] only while guidance is running. The cluster's third event, skipping a
     * waypoint, has nothing to act on: routes here have a single waypoint.
     */
    data object StartNavigation : BikeControlEvent

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
     * Answered by resending everything the cluster shows, and the phone battery level.
     * This arrives without a BLE reconnect, so it is a different signal from
     * [BikeConnectionState.Connected] — the link never dropped, but the display forgot
     * everything it was showing.
     */
    data object ClusterReady : BikeControlEvent
}
