package com.spaceboy.ridebuddy.domain

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
    fun write(characteristic: UUID, payload: ByteArray): Boolean

    /**
     * Queues a write and waits for Android's GATT callback when the characteristic supports one.
     * Implementations return false when the write cannot be queued, is rejected, or times out.
     */
    suspend fun writeAndAwait(characteristic: UUID, payload: ByteArray): Boolean = false

    /**
     * Queues a write with its required acknowledgement behavior and awaits its outcome.
     * The legacy overload keeps existing implementations source-compatible.
     */
    suspend fun writeAndAwait(write: BikeWrite): Boolean =
        writeAndAwait(write.characteristic, write.payload)

    fun writeBatch(writes: List<BikeWrite>, priority: Boolean = false): Boolean {
        writes.forEach { write -> write(write.characteristic, write.payload) }
        return true
    }
}

sealed interface BikeConnectionState {
    data object Disconnected : BikeConnectionState
    data object Scanning : BikeConnectionState
    data class Connecting(val deviceName: String?) : BikeConnectionState
    data class Authenticating(val deviceName: String) : BikeConnectionState
    data class Connected(val deviceName: String, val rssi: Int?) : BikeConnectionState
    data class Failed(val message: String) : BikeConnectionState
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

data class BleDiagnostics(
    val authenticated: Boolean = false,
    val protectionPhase: ProtectionPhase = ProtectionPhase.Idle,
    val protectionPath: ProtectionPath? = null,
    val bonded: Boolean? = null,
    val negotiatedMtu: Int? = null,
    val servicesDiscovered: Int = 0,
    val notificationsReceived: Long = 0,
    val writesCompleted: Long = 0,
    val descriptorWritesCompleted: Long = 0,
    val readsCompleted: Long = 0,
    val activeGattOperation: String? = null,
    val malformedTelemetryFrames: Long = 0,
    val lastFrameAtMillis: Long? = null,
    val rssi: Int? = null,
    val telemetryHz: Double = 0.0,
    val lastError: String? = null,
    val lastErrorAtMillis: Long? = null,
    val serviceSnapshot: List<String> = emptyList(),
    val recentFrames: List<String> = emptyList(),
    val recentEvents: List<String> = emptyList(),
)

sealed interface BikeControlEvent {
    data object SkipManeuver : BikeControlEvent
    data object ExitNavigation : BikeControlEvent
    data class CallAction(val code: Int) : BikeControlEvent
}
