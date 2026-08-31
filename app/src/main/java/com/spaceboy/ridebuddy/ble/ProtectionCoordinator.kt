package com.spaceboy.ridebuddy.ble

import android.bluetooth.BluetoothGattCharacteristic
import android.os.Handler
import android.os.SystemClock
import com.spaceboy.ridebuddy.domain.ConnectionFailureCategory
import com.spaceboy.ridebuddy.domain.ProtectionPath
import com.spaceboy.ridebuddy.domain.ProtectionPhase
import java.util.UUID

/**
 * Applies [ProtectionSession] actions to the serialized GATT transport.
 *
 * The split is deliberate: the state machine decides, this class acts. What lives here is
 * everything that needs Android — characteristic lookup, queueing, the two timeouts — plus
 * the acceptance side effects and the gate that holds readiness until the whole
 * subscription set is up.
 *
 * Collaborators arrive as function references rather than a back-reference to the
 * connection, which keeps the dependency one-directional and lets the coordinator be
 * driven directly in tests.
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
    private val onFailure: (String, ConnectionFailureCategory) -> Unit,
    private val onReconnectRequired: (String) -> Unit,
    private val updateDiagnostics: (ProtectionPhase, ProtectionPath?) -> Unit,
    private val log: (String) -> Unit,
) {
    private val verificationTimeoutToken = Any()
    private val challengeTimeoutToken = Any()
    private var session: ProtectionSession? = null
    private var postAuthenticationGate: PostAuthenticationGate? = null
    private var verificationArmed = false

    val phase: ProtectionPhase
        get() = session?.phase ?: ProtectionPhase.Idle

    /** Starts a handshake for a freshly discovered profile. */
    fun begin(previouslyAccepted: Boolean) {
        val started = ProtectionSession(previouslyAccepted = previouslyAccepted)
        session = started
        syncDiagnostics()
        log("Services ready; authenticating${if (previouslyAccepted) " with stored acceptance" else ""}")
        handle(started.begin())
    }

    /** Drops all handshake state and pending timeouts. Called on every teardown. */
    fun reset() {
        handler.removeCallbacksAndMessages(verificationTimeoutToken)
        handler.removeCallbacksAndMessages(challengeTimeoutToken)
        session = null
        postAuthenticationGate = null
        verificationArmed = false
    }

    fun onChallenge(value: ByteArray) {
        log("Protection challenge received by indication")
        handler.removeCallbacksAndMessages(challengeTimeoutToken)
        handle(
            session?.onChallenge(value)
                ?: ProtectionAction.Fail("Authentication session is missing"),
        )
    }

    /**
     * Routes a completed subscription. The challenge characteristic advances the handshake
     * itself; everything else feeds the readiness gate.
     */
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
            // Evidence already in hand authenticates the session immediately; otherwise the
            // deadline below waits for the first indication that proves the link is live.
            gateUpdate.deferredEvidence?.let(::completeEvidence)
            armVerificationTimeout()
        }
    }

    /**
     * Arms the verification deadline at most once per session, and only while unauthenticated.
     *
     * What satisfies it is any indication the cluster sends unprompted — telemetry, which arrives
     * at roughly 4 Hz, well inside the deadline. The identity characteristics also indicate, but
     * on the cluster's own schedule rather than on subscription, so nothing waits for them.
     */
    private fun armVerificationTimeout() {
        if (verificationArmed || isAuthenticated()) return
        verificationArmed = true
        scheduleVerificationTimeout()
    }

    fun onProtectionResponseWritten() {
        log("Protection response write completed")
        val action = session?.onProtectionResponseWritten()
            ?: ProtectionAction.Fail("Authentication session is missing")
        // Record acceptance as soon as the write is confirmed rather than waiting for the
        // session to finish coming up. The cluster will not issue a second challenge after
        // accepting one, so a link that drops between here and readiness must still take
        // the stored-acceptance path on its next attempt.
        if (action !is ProtectionAction.Fail) markAccepted()
        handle(action)
    }

    /**
     * Offers proof that the link is live. Held by the gate until every required
     * subscription has completed, then replayed.
     */
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
                ProtectionFailurePolicy.Stop,
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
                    onFailure("Authentication challenge endpoint is missing", ConnectionFailureCategory.Deterministic)
                } else {
                    enqueue(GattOperation.Subscribe(challenge))
                }
            }

            is ProtectionAction.WriteResponse -> {
                handler.removeCallbacksAndMessages(challengeTimeoutToken)
                val response = characteristic(BleCharacteristics.ProtectionResponse)
                if (response == null) {
                    onFailure("Authentication response endpoint is missing", ConnectionFailureCategory.Deterministic)
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

    /**
     * Queues the subscription set.
     *
     * The set is queued as one batch so it drains in the fixed order
     * [BleCharacteristics.PostAuthenticationSubscriptions] defines. Subscriptions are the whole
     * of post-authentication setup: nothing is read, because a capture shows both identity
     * characteristics answering a read with a zero-filled buffer and delivering their real
     * values as indications instead — see `docs/cluster-link-decisions.md` (D4).
     */
    private fun beginPostAuthenticationVerification() {
        log("Starting post-authentication verification via ${session?.path?.name ?: "unknown path"}")
        val subscriptions = BleCharacteristics.PostAuthenticationSubscriptions.map { uuid ->
            GattOperation.Subscribe(
                characteristic(uuid) ?: run {
                    onFailure(
                        "Motorcycle companion profile lost ${uuid.shortName()}",
                        ConnectionFailureCategory.Deterministic,
                    )
                    return
                },
            )
        }
        postAuthenticationGate = PostAuthenticationGate(BleCharacteristics.PostAuthenticationSubscriptions)
        enqueueAll(subscriptions)
    }

    private fun completeAuthentication(evidence: String) {
        if (isAuthenticated()) return
        handler.removeCallbacksAndMessages(verificationTimeoutToken)
        markAccepted()
        onAuthenticated(evidence, session?.path)
    }

    private fun handleFailure(failure: ProtectionAction.Fail) {
        when (failure.policy) {
            // A profile or endpoint problem is deterministic; it is not the bike rejecting us.
            ProtectionFailurePolicy.Stop ->
                onFailure(failure.message, ConnectionFailureCategory.Deterministic)

            // Only this policy is reached by a confirmed protocol-level rejection.
            ProtectionFailurePolicy.ClearAcceptance -> {
                clearAcceptance()
                onFailure(failure.message, ConnectionFailureCategory.AuthenticationRejected)
            }

            ProtectionFailurePolicy.Reconnect -> onReconnectRequired(failure.message)
        }
    }

    private fun syncDiagnostics() {
        val current = session
        updateDiagnostics(
            current?.phase ?: ProtectionPhase.Idle,
            current?.path,
        )
    }

    /**
     * Fails the attempt if the cluster never speaks after its subscriptions are enabled.
     *
     * Both timeouts capture the generation and session identity and recheck them when they
     * fire, so a timeout scheduled for a connection that has since been torn down and
     * replaced cannot fail its successor.
     */
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

    /** Fails the attempt if the challenge never arrives after its subscription is enabled. */
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
        /** Generous next to the sub-second response the cluster gives when it is healthy. */
        const val VerificationTimeoutMillis = 8_000L
        const val ChallengeTimeoutMillis = 8_000L
    }
}
