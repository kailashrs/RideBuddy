package com.spaceboy.ridebuddy.ble

import com.spaceboy.ridebuddy.domain.BleDiagnostics
import com.spaceboy.ridebuddy.domain.ConnectionAttemptContext
import com.spaceboy.ridebuddy.domain.ConnectionFailure
import com.spaceboy.ridebuddy.domain.ConnectionFailureCategory
import com.spaceboy.ridebuddy.domain.LinkSnapshot
import com.spaceboy.ridebuddy.domain.ProtectionPath
import com.spaceboy.ridebuddy.domain.ProtectionPhase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Single owner of the observable [BleDiagnostics] snapshot.
 *
 * Keeping every mutation behind a named method is what makes the retention rules enforceable:
 * teardown clears live link state but never the recorded failure or the last successful link, and
 * only [recordFailure] can replace a failure that is already on record.
 */
internal class BleDiagnosticsRecorder(initialEvents: List<String> = emptyList()) {
    private val state = MutableStateFlow(BleDiagnostics(recentEvents = initialEvents))

    val diagnostics: StateFlow<BleDiagnostics> = state.asStateFlow()
    val value: BleDiagnostics get() = state.value

    fun recordEvents(events: List<String>) {
        state.update { it.copy(recentEvents = events) }
    }

    fun setActiveOperation(label: String?) {
        state.update { it.copy(activeGattOperation = label) }
    }

    /** Replaces the recorded failure. Automatic reattempts must not call this. */
    fun recordFailure(failure: ConnectionFailure) {
        state.update { it.copy(lastFailure = failure, suppressionReason = null) }
    }

    /** Records a controller-side failure that did not originate from a GATT callback. */
    fun recordFailure(
        message: String,
        category: ConnectionFailureCategory,
        context: ConnectionAttemptContext,
    ) {
        recordFailure(
            ConnectionFailure(
                message = message,
                category = category,
                atMillis = System.currentTimeMillis(),
                context = context,
            ),
        )
    }

    /**
     * Records why automatic attempts stopped without overwriting the failure that caused them to.
     */
    fun recordSuppression(
        reason: String,
        category: ConnectionFailureCategory,
        context: ConnectionAttemptContext,
    ) {
        state.update { diagnostics ->
            diagnostics.copy(
                suppressionReason = reason,
                lastFailure = diagnostics.lastFailure ?: ConnectionFailure(
                    message = reason,
                    category = category,
                    atMillis = System.currentTimeMillis(),
                    context = context,
                ),
            )
        }
    }

    /** Called when a connection succeeds, so a stale failure is not shown alongside a live link. */
    fun clearFailure() {
        state.update { it.copy(lastFailure = null, suppressionReason = null) }
    }

    fun updateAttempt(context: ConnectionAttemptContext) {
        state.update { it.copy(attempt = context) }
    }

    /** Resets the per-attempt view of the link while leaving recorded history intact. */
    fun beginConnectionAttempt(bonded: Boolean?, context: ConnectionAttemptContext) {
        state.update { diagnostics ->
            diagnostics.copy(
                authenticated = false,
                protectionPhase = ProtectionPhase.Idle,
                protectionPath = null,
                bonded = bonded,
                attMtu = null,
                servicesDiscovered = 0,
                rssi = null,
                activeGattOperation = null,
                attempt = context,
                suppressionReason = null,
            )
        }
    }

    fun markBonded() {
        state.update { it.copy(bonded = true) }
    }

    fun setAttMtu(mtu: Int) {
        state.update { it.copy(attMtu = mtu) }
    }

    fun setServices(serviceCount: Int, characteristicLabels: List<String>) {
        state.update {
            it.copy(servicesDiscovered = serviceCount, serviceSnapshot = characteristicLabels)
        }
    }

    fun setRssi(rssi: Int) {
        state.update { it.copy(rssi = rssi) }
    }

    fun setProtection(phase: ProtectionPhase, path: ProtectionPath?) {
        state.update { it.copy(protectionPhase = phase, protectionPath = path) }
    }

    /**
     * The session is fully up. Clears any recorded failure and suppression reason: they
     * described attempts that have now been superseded by a working link.
     */
    fun markAuthenticated(path: ProtectionPath?) {
        state.update {
            it.copy(
                authenticated = true,
                protectionPhase = ProtectionPhase.Ready,
                protectionPath = path,
                lastFailure = null,
                suppressionReason = null,
            )
        }
    }

    fun countDescriptorWrite() {
        state.update { it.copy(descriptorWritesCompleted = it.descriptorWritesCompleted + 1) }
    }

    fun countWrite() {
        state.update { it.copy(writesCompleted = it.writesCompleted + 1) }
    }

    fun recordNotification(frameLine: String, receivedAtMillis: Long) {
        state.update { it.withFrame(frameLine, receivedAtMillis) }
    }

    /**
     * Telemetry is the only characteristic that notifies continuously, so its frame record, rate
     * and validity are published as one snapshot rather than three.
     */
    fun recordTelemetryNotification(
        frameLine: String,
        receivedAtMillis: Long,
        telemetryHz: Double,
        droppedRawTelemetryFrames: Long,
        malformed: Boolean,
    ) {
        state.update { diagnostics ->
            diagnostics.withFrame(frameLine, receivedAtMillis).copy(
                telemetryHz = telemetryHz,
                droppedRawTelemetryFrames = droppedRawTelemetryFrames,
                malformedTelemetryFrames = diagnostics.malformedTelemetryFrames + if (malformed) 1L else 0L,
            )
        }
    }

    /** Shared frame bookkeeping: the counter, the freshness stamp, and the newest-first ring. */
    private fun BleDiagnostics.withFrame(frameLine: String, receivedAtMillis: Long) = copy(
        notificationsReceived = notificationsReceived + 1,
        lastFrameAtMillis = receivedAtMillis,
        recentFrames = (listOf(frameLine) + recentFrames).take(MaxFrameEntries),
    )

    /**
     * Clears live link state. A link that reached an authenticated session is preserved as
     * [BleDiagnostics.lastSuccessfulLink] so the diagnostics screen can still show what the last
     * working connection negotiated.
     */
    fun resetForTeardown(
        sessionId: Long?,
        establishedAtMillis: Long?,
        durationMillis: Long?,
    ) {
        state.update { diagnostics ->
            val snapshot = if (diagnostics.authenticated) {
                LinkSnapshot(
                    sessionId = sessionId ?: diagnostics.attempt.sessionId ?: 0L,
                    attMtu = diagnostics.attMtu,
                    servicesDiscovered = diagnostics.servicesDiscovered,
                    serviceSnapshot = diagnostics.serviceSnapshot,
                    establishedAtMillis = establishedAtMillis,
                    durationMillis = durationMillis,
                )
            } else {
                diagnostics.lastSuccessfulLink
            }
            diagnostics.copy(
                authenticated = false,
                protectionPhase = ProtectionPhase.Idle,
                attMtu = null,
                servicesDiscovered = 0,
                serviceSnapshot = emptyList(),
                lastFrameAtMillis = null,
                rssi = null,
                telemetryHz = 0.0,
                droppedRawTelemetryFrames = 0,
                activeGattOperation = null,
                lastSuccessfulLink = snapshot,
            )
        }
    }

    private companion object {
        /** Frames kept for display. Small: at telemetry rate this is a few seconds of history. */
        const val MaxFrameEntries = 30
    }
}
