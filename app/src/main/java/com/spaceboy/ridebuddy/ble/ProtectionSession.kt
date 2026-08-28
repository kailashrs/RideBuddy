package com.spaceboy.ridebuddy.ble

import com.spaceboy.ridebuddy.domain.ProtectionPath
import com.spaceboy.ridebuddy.domain.ProtectionPhase

internal enum class ProtectionFailurePolicy {
    /** Terminal for this attempt: the profile or endpoint cannot support the handshake. */
    Stop,

    /**
     * The bike rejected the credential itself. This is the only policy allowed to discard stored
     * protection acceptance; stale local state and dropped links must never reach it.
     */
    ClearAcceptance,

    /** Retire the link and try again with a clean session, keeping stored acceptance. */
    Reconnect,
}

internal sealed interface ProtectionAction {
    data object None : ProtectionAction
    data object SubscribeChallenge : ProtectionAction
    data class WriteResponse(val value: ByteArray) : ProtectionAction
    data object BeginPostAuthentication : ProtectionAction
    data class CompleteAuthentication(val evidence: String) : ProtectionAction
    data class Fail(
        val message: String,
        val policy: ProtectionFailurePolicy = ProtectionFailurePolicy.Stop,
    ) : ProtectionAction
}

/**
 * Pure state machine for the cluster's protection handshake. Android GATT operations remain owned
 * by [AndroidBikeConnection]; this class accepts protocol events and returns the next serialized
 * side effect. Stale duplicate callbacks are ignored, while events that would create an impossible
 * state fail explicitly.
 *
 * Two things here are worth separating. Answering a recognised challenge whenever it arrives is
 * OEM-derived: the India app handles `8610` from a single stateless callback with no notion of a
 * phase, so it answers a late challenge exactly as it answers the first. Everything about
 * *resuming* afterwards — [Resume], [State.Responding.deferredEvidence], and the verification
 * timeout they interact with — is this app's own addition, because this app has a verification
 * phase that the OEM does not. Those paths are defensive: no capture shows the cluster issuing a
 * second challenge, and the OEM code cannot tell us whether it does.
 */
internal class ProtectionSession(
    private val previouslyAccepted: Boolean,
) {
    private sealed interface State {
        data object Idle : State
        data object SubscribingChallenge : State
        data object AwaitingChallenge : State
        data class Responding(
            val pendingChallenges: List<ByteArray>,
            val resume: Resume,
            val deferredEvidence: String? = null,
        ) : State

        data class Verifying(val path: ProtectionPath) : State
        data class Ready(val path: ProtectionPath) : State
    }

    /**
     * Where a completed response write returns to.
     *
     * [BeginVerification] is the observed case: the first challenge of a fresh pairing. The other
     * two describe a challenge arriving after verification has already started, which has not been
     * observed on hardware. They are reachable only on the fresh-pairing path, which stays
     * subscribed to the challenge endpoint for the life of the link; the stored-acceptance path
     * never subscribes to it, so no challenge can arrive there at all.
     */
    private sealed interface Resume {
        val path: ProtectionPath

        data object BeginVerification : Resume {
            override val path = ProtectionPath.ChallengeIndication
        }

        data class ContinueVerification(override val path: ProtectionPath) : Resume
        data class ContinueReady(override val path: ProtectionPath) : Resume
    }

    private var state: State = State.Idle

    val phase: ProtectionPhase
        get() = when (state) {
            State.Idle -> ProtectionPhase.Idle
            State.SubscribingChallenge -> ProtectionPhase.SubscribingChallenge
            State.AwaitingChallenge -> ProtectionPhase.AwaitingChallenge
            is State.Responding -> ProtectionPhase.Responding
            is State.Verifying -> ProtectionPhase.Verifying
            is State.Ready -> ProtectionPhase.Ready
        }

    val path: ProtectionPath?
        get() = when (val current = state) {
            is State.Responding -> current.resume.path
            is State.Verifying -> current.path
            is State.Ready -> current.path
            else -> null
        }

    fun begin(): ProtectionAction = when (state) {
        State.Idle -> if (previouslyAccepted) {
            state = State.Verifying(ProtectionPath.StoredAcceptance)
            ProtectionAction.BeginPostAuthentication
        } else {
            state = State.SubscribingChallenge
            ProtectionAction.SubscribeChallenge
        }

        else -> ProtectionAction.None
    }

    /**
     * The indication can arrive before Android reports the successful CCCD write, so every state
     * other than [State.SubscribingChallenge] is a callback that has already been overtaken.
     */
    fun onChallengeSubscriptionReady(): ProtectionAction {
        if (state == State.SubscribingChallenge) state = State.AwaitingChallenge
        return ProtectionAction.None
    }

    fun onChallenge(value: ByteArray): ProtectionAction {
        val response = ProtectionHandshake.responseFor(value)
            ?: return unsupportedChallengeFailure()

        return when (val current = state) {
            State.SubscribingChallenge,
            State.AwaitingChallenge,
            -> startResponse(value, response, Resume.BeginVerification)

            is State.Responding -> if (
                current.pendingChallenges.any { pending -> pending.contentEquals(value) }
            ) {
                ProtectionAction.None
            } else {
                state = current.copy(
                    pendingChallenges = current.pendingChallenges + listOf(value.copyOf()),
                )
                ProtectionAction.WriteResponse(response)
            }

            is State.Verifying -> startResponse(
                value,
                response,
                Resume.ContinueVerification(current.path),
            )

            is State.Ready -> startResponse(
                value,
                response,
                Resume.ContinueReady(current.path),
            )

            // Our own session bookkeeping is stale, not the bike rejecting us. Retire the link
            // and start a clean handshake; the stored acceptance is still valid.
            State.Idle -> ProtectionAction.Fail(
                "Authentication challenge arrived before the session began; restarting the link",
                ProtectionFailurePolicy.Reconnect,
            )
        }
    }

    fun onProtectionResponseWritten(): ProtectionAction {
        // A response callback with nothing pending is a stale or duplicated callback, not a
        // rejection: the bike never told us the credential was wrong.
        val responding = state as? State.Responding ?: return ProtectionAction.Fail(
            "Authentication response completed without a pending challenge; restarting the link",
            ProtectionFailurePolicy.Reconnect,
        )
        if (responding.pendingChallenges.size > 1) {
            state = responding.copy(pendingChallenges = responding.pendingChallenges.drop(1))
            return ProtectionAction.None
        }
        return when (val resume = responding.resume) {
            Resume.BeginVerification -> {
                state = State.Verifying(resume.path)
                ProtectionAction.BeginPostAuthentication
            }

            is Resume.ContinueVerification -> {
                val evidence = responding.deferredEvidence
                if (evidence == null) {
                    state = State.Verifying(resume.path)
                    ProtectionAction.None
                } else {
                    state = State.Ready(resume.path)
                    ProtectionAction.CompleteAuthentication(evidence)
                }
            }

            is Resume.ContinueReady -> {
                state = State.Ready(resume.path)
                ProtectionAction.None
            }
        }
    }

    fun onPostAuthenticationEvidence(evidence: String): ProtectionAction = when (val current = state) {
        is State.Verifying -> {
            state = State.Ready(current.path)
            ProtectionAction.CompleteAuthentication(evidence)
        }

        // Defensive rather than observed. If a challenge ever does interrupt verification, the
        // evidence that would finish it must survive the in-flight response: acting on it would
        // claim a session the bike has not acknowledged, and dropping it would let this app's own
        // verification timeout retire a healthy link. So it is held for the write callback.
        is State.Responding -> {
            if (current.isAwaitingFirstEvidence()) {
                state = current.copy(deferredEvidence = evidence)
            }
            ProtectionAction.None
        }

        else -> ProtectionAction.None
    }

    fun onRequiredProfileFailure(message: String): ProtectionAction = ProtectionAction.Fail(
        message,
        ProtectionFailurePolicy.Stop,
    )

    fun onVerificationTimeout(): ProtectionAction = when (val current = state) {
        is State.Verifying -> verificationTimeoutFailure()
        is State.Responding ->
            if (current.isAwaitingFirstEvidence()) verificationTimeoutFailure() else ProtectionAction.None

        else -> ProtectionAction.None
    }

    fun onChallengeTimeout(): ProtectionAction = if (state == State.AwaitingChallenge) {
        ProtectionAction.Fail(
            "Motorcycle authentication challenge timed out",
            ProtectionFailurePolicy.Reconnect,
        )
    } else {
        ProtectionAction.None
    }

    /**
     * Re-authenticating mid-verification, with the evidence that would finish it still missing.
     * The same condition decides both that evidence is worth holding and that a verification
     * timeout is a real failure rather than a write that is about to complete. See [Resume] for
     * why this state is defensive rather than something the cluster is known to produce.
     */
    private fun State.Responding.isAwaitingFirstEvidence(): Boolean =
        resume is Resume.ContinueVerification && deferredEvidence == null

    private fun startResponse(
        challenge: ByteArray,
        response: ByteArray,
        resume: Resume,
    ): ProtectionAction {
        state = State.Responding(listOf(challenge.copyOf()), resume)
        return ProtectionAction.WriteResponse(response)
    }

    // The bike itself refused our protocol: it offered a challenge this build cannot answer.
    // That is a peer-side rejection, so the stored shortcut is genuinely no longer valid.
    private fun unsupportedChallengeFailure(): ProtectionAction = ProtectionAction.Fail(
        "Bike sent an unsupported authentication challenge",
        ProtectionFailurePolicy.ClearAcceptance,
    )

    private fun verificationTimeoutFailure(): ProtectionAction = ProtectionAction.Fail(
        "Protected session verification timed out",
        ProtectionFailurePolicy.Reconnect,
    )
}
