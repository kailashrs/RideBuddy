package com.spaceboy.ridebuddy.ble

import android.bluetooth.BluetoothGattCharacteristic
import android.os.Handler
import android.os.SystemClock
import com.spaceboy.ridebuddy.domain.ProtectionPath
import com.spaceboy.ridebuddy.domain.ProtectionPhase
import java.util.UUID

/**
 * Applies [ProtectionSession] actions to the serialized GATT transport.
 *
 * The pure state machine stays independently testable; this coordinator owns only its Android
 * timeouts, required profile setup, and acceptance side effects.
 */
internal class ProtectionCoordinator(
    private val handler: Handler,
    private val currentGeneration: () -> Long,
    private val characteristic: (UUID) -> BluetoothGattCharacteristic?,
    private val enqueue: (GattOperation) -> Unit,
    private val enqueueAll: (List<GattOperation>) -> Unit,
    private val isAuthenticated: () -> Boolean,
    private val markAccepted: () -> Unit,
    private val clearAcceptance: () -> Unit,
    private val onAuthenticated: (String, ProtectionPath?) -> Unit,
    private val onFailure: (String) -> Unit,
    private val onReconnectRequired: (String) -> Unit,
    private val updateDiagnostics: (ProtectionPhase, ProtectionPath?) -> Unit,
    private val log: (String) -> Unit,
) {
    private val verificationTimeoutToken = Any()
    private val challengeTimeoutToken = Any()
    private var session: ProtectionSession? = null
    private var postAuthenticationGate: PostAuthenticationGate? = null

    val phase: ProtectionPhase
        get() = session?.phase ?: ProtectionPhase.Idle

    fun begin(previouslyAccepted: Boolean) {
        session = ProtectionSession(previouslyAccepted = previouslyAccepted)
        syncDiagnostics()
        log("Services ready; authenticating${if (previouslyAccepted) " with stored acceptance" else ""}")
        handle(session?.begin() ?: ProtectionAction.Fail("Authentication session is missing"))
    }

    fun reset() {
        handler.removeCallbacksAndMessages(verificationTimeoutToken)
        handler.removeCallbacksAndMessages(challengeTimeoutToken)
        session = null
        postAuthenticationGate = null
    }

    fun onChallenge(value: ByteArray) {
        log("Protection challenge received by indication")
        handler.removeCallbacksAndMessages(challengeTimeoutToken)
        handle(
            session?.onChallenge(value)
                ?: ProtectionAction.Fail("Authentication session is missing"),
        )
    }

    fun onSubscriptionCompleted(uuid: UUID) {
        log("Subscribed ${uuid.shortName()}")
        if (uuid == BleCharacteristics.ProtectionChallenge) {
            handle(
                session?.onChallengeSubscriptionReady()
                    ?: ProtectionAction.Fail("Authentication session is missing"),
            )
            if (session?.phase == ProtectionPhase.AwaitingChallenge) scheduleChallengeTimeout()
            return
        }

        val gateUpdate = postAuthenticationGate?.markSubscriptionEnabled(uuid) ?: return
        if (gateUpdate.becameReady) {
            log("Required motorcycle subscriptions are ready")
            gateUpdate.deferredEvidence?.let(::completeEvidence)
            if (!isAuthenticated()) scheduleVerificationTimeout()
        }
    }

    fun onProtectionResponseWritten() {
        log("Protection response write completed")
        val action = session?.onProtectionResponseWritten()
            ?: ProtectionAction.Fail("Authentication session is missing")
        if (action !is ProtectionAction.Fail) markAccepted()
        handle(action)
    }

    fun acceptEvidence(evidence: String) {
        val gate = postAuthenticationGate ?: return
        val acceptedEvidence = gate.acceptEvidence(evidence)
        if (acceptedEvidence == null) {
            log("Deferred $evidence until required subscriptions are ready")
            return
        }
        completeEvidence(acceptedEvidence)
    }

    fun onRequiredProfileFailure(uuid: UUID) {
        handle(
            session?.onRequiredProfileFailure(
                "Could not enable required motorcycle data (${uuid.shortName()})",
            ) ?: ProtectionAction.Fail(
                "Protection session is missing after a required subscription failure",
                ProtectionFailurePolicy.ClearAcceptanceAndReconnect,
            ),
        )
    }

    private fun completeEvidence(evidence: String) {
        handle(session?.onPostAuthenticationEvidence(evidence) ?: ProtectionAction.None)
    }

    private fun handle(action: ProtectionAction) {
        syncDiagnostics()
        when (action) {
            ProtectionAction.None -> Unit
            ProtectionAction.SubscribeChallenge -> {
                val challenge = characteristic(BleCharacteristics.ProtectionChallenge)
                if (challenge == null) {
                    onFailure("Authentication challenge endpoint is missing")
                } else {
                    enqueue(GattOperation.Subscribe(challenge))
                }
            }

            is ProtectionAction.WriteResponse -> {
                handler.removeCallbacksAndMessages(challengeTimeoutToken)
                val response = characteristic(BleCharacteristics.ProtectionResponse)
                if (response == null) {
                    onFailure("Authentication response endpoint is missing")
                } else {
                    log("Queueing protection response")
                    enqueue(
                        GattOperation.Write(
                            response,
                            action.value.copyOf(),
                            priority = GattOperationPriority.Critical,
                        ),
                    )
                }
            }

            ProtectionAction.BeginPostAuthentication -> beginPostAuthenticationVerification()
            is ProtectionAction.CompleteAuthentication -> completeAuthentication(action.evidence)
            is ProtectionAction.Fail -> handleFailure(action)
        }
        syncDiagnostics()
    }

    private fun beginPostAuthenticationVerification() {
        syncDiagnostics()
        log("Starting post-authentication verification via ${session?.path?.name ?: "unknown path"}")
        val subscriptions = BleCharacteristics.PostAuthenticationSubscriptions.map { uuid ->
            GattOperation.Subscribe(
                characteristic(uuid) ?: run {
                    onFailure("Motorcycle companion profile lost ${uuid.shortName()}")
                    return
                },
            )
        }
        postAuthenticationGate = PostAuthenticationGate(BleCharacteristics.PostAuthenticationSubscriptions)
        enqueueAll(subscriptions)
        val identityReads = BleCharacteristics.PostAuthenticationIdentityReads.mapNotNull { uuid ->
            characteristic(uuid)?.takeIf { candidate ->
                candidate.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0
            }?.let(GattOperation::Read)
        }
        if (identityReads.isNotEmpty()) {
            log("Queued ${identityReads.size} motorcycle identity snapshot reads")
            enqueueAll(identityReads)
        }
    }

    private fun completeAuthentication(evidence: String) {
        if (isAuthenticated()) return
        handler.removeCallbacksAndMessages(verificationTimeoutToken)
        markAccepted()
        onAuthenticated(evidence, session?.path)
    }

    private fun handleFailure(failure: ProtectionAction.Fail) {
        when (failure.policy) {
            ProtectionFailurePolicy.Stop -> onFailure(failure.message)
            ProtectionFailurePolicy.ClearAcceptance -> {
                clearAcceptance()
                onFailure(failure.message)
            }

            ProtectionFailurePolicy.ClearAcceptanceAndReconnect -> {
                clearAcceptance()
                onReconnectRequired(failure.message)
            }
        }
    }

    private fun syncDiagnostics() {
        val current = session
        updateDiagnostics(
            current?.phase ?: ProtectionPhase.Idle,
            current?.path,
        )
    }

    private fun scheduleVerificationTimeout() {
        handler.removeCallbacksAndMessages(verificationTimeoutToken)
        val currentSession = session ?: return
        val generation = currentGeneration()
        handler.postAtTime({
            if (generation == currentGeneration() && session === currentSession) {
                handle(currentSession.onVerificationTimeout())
            }
        }, verificationTimeoutToken, SystemClock.uptimeMillis() + VerificationTimeoutMillis)
    }

    private fun scheduleChallengeTimeout() {
        handler.removeCallbacksAndMessages(challengeTimeoutToken)
        val currentSession = session ?: return
        val generation = currentGeneration()
        handler.postAtTime({
            if (generation == currentGeneration() && session === currentSession) {
                handle(currentSession.onChallengeTimeout())
            }
        }, challengeTimeoutToken, SystemClock.uptimeMillis() + ChallengeTimeoutMillis)
    }

    private companion object {
        const val VerificationTimeoutMillis = 8_000L
        const val ChallengeTimeoutMillis = 8_000L
    }
}
