package com.spaceboy.ridebuddy.domain

import androidx.compose.runtime.Immutable
import com.spaceboy.ridebuddy.ble.TelemetryFrame
import com.spaceboy.ridebuddy.ble.BikeConnectionTarget
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharedFlow
import java.util.UUID

/** Selects the GATT acknowledgement behavior required by a packet family. */
enum class BikeWriteMode {
    Default,
    NoResponsePreferred,
}

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

data class TelemetryReading(
    val frame: TelemetryFrame,
    val receivedAtMillis: Long,
    val receivedAtElapsedRealtime: Long,
)

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
    fun notifyStartFailed(message: String = "Unable to start connection service") {}
    fun enqueueWrite(characteristic: UUID, payload: ByteArray)

    /**
     * Queues a write with its required acknowledgement behavior and awaits its outcome.
     * Implementations return false when the write cannot be queued, is rejected, or times out.
     */
    suspend fun writeAndAwait(write: BikeWrite): Boolean
}

sealed interface BikeConnectionState {
    data object Disconnected : BikeConnectionState
    data object Scanning : BikeConnectionState
    /**
     * GATT transport is being established. The optional [reconnectAttempt] / [maxAttempts]
     * pair is non-null only on the exponential-backoff retry path so the UI can render a
     * "Reconnecting (3/6)" indicator; the initial connect attempt leaves both null.
     */
    data class Connecting(
        val deviceName: String?,
        val reconnectAttempt: Int? = null,
        val maxAttempts: Int? = null,
    ) : BikeConnectionState
    data class Authenticating(val deviceName: String) : BikeConnectionState
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

data class BikeIdentity(
    val vin: String? = null,
    val clusterSoftwareVersion: String? = null,
    val lastConnectedAtMillis: Long? = null,
)

enum class ProtectionPhase {
    Idle,
    SubscribingChallenge,
    AwaitingChallenge,
    Responding,
    Verifying,
    Ready,
}

enum class ProtectionPath {
    StoredAcceptance,
    ChallengeIndication,
}

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
    val readsCompleted: Long = 0,
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

sealed interface BikeControlEvent {
    /**
     * The rider pressed the handlebar control while the cluster showed **GO**.
     *
     * The OEM handles this in its route-preview screen, where it clicks its own GO button; the
     * guidance screen handles [SkipManeuver] and [ExitNavigation] instead. Which one is listening
     * is what makes the same button mean three things.
     */
    data object StartNavigation : BikeControlEvent

    data object SkipManeuver : BikeControlEvent
    data object ExitNavigation : BikeControlEvent

    /** Answer (1) or reject/end (0) from the handlebar, while a call is up. */
    data class CallAction(val code: Int) : BikeControlEvent

    /**
     * The cluster is asserting that a call is live and answered.
     *
     * The OEM marks the call active, answered and incoming and writes nothing back. The point of
     * it is the first of those: the same flag gates its answer and reject handling, so until this
     * arrives — or the phone itself sees a call — a handlebar press does nothing.
     */
    data object ClusterCallActive : BikeControlEvent

    /**
     * The cluster has come up and wants the phone's state again. The OEM answers by writing the
     * current call state and clearing the notification icons; it can arrive without a BLE
     * reconnect, so it is not the same signal as [BikeConnectionState.Connected].
     */
    data object ClusterReady : BikeControlEvent
}
