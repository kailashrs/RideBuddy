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

/**
 * The single side effect a protocol event calls for. Returning one of these instead of
 * performing it is what keeps [ProtectionSession] free of Android and exhaustively testable.
 */
internal sealed interface ProtectionAction {
    /** Nothing to do — typically a duplicate or already-overtaken callback. */
    data object None : ProtectionAction

    data object SubscribeChallenge : ProtectionAction
    data class WriteResponse(val value: ByteArray) : ProtectionAction

    /** Authentication is done; enable the session's normal subscription set. */
    data object BeginPostAuthentication : ProtectionAction

    /** The subscription set is live and proven; the session may be presented as connected. */
    data class CompleteAuthentication(val evidence: String) : ProtectionAction

    data class Fail(
        val message: String,
        val policy: ProtectionFailurePolicy = ProtectionFailurePolicy.Stop,
    ) : ProtectionAction
}

/**
 * Pure state machine for the cluster's protection handshake.
 *
 * There are two ways through it. A first connection subscribes to the challenge
 * characteristic, waits for the cluster to issue a challenge, and writes back the matching
 * response. A later connection to a bike that has already accepted one skips straight to
 * verification, because the cluster will not issue a second challenge and waiting for one
 * would stall.
 *
 * Either path ends the same way: the normal subscription set is enabled, and the session is
 * only presented as authenticated once that set is proven live.
 *
 * The class accepts protocol events and returns the next side effect; the GATT operations
 * themselves stay with [AndroidBikeConnection]. Stale duplicate callbacks are ignored,
 * while events that would create an impossible state fail explicitly.
 *
 * A challenge arriving after verification has begun is treated as a reason to restart the
 * link rather than a state to resume from. It has not been observed on hardware, and it
 * cannot happen at all on the stored-acceptance path, which never subscribes to the
 * challenge characteristic. Restarting costs one reconnect and preserves stored
 * acceptance, so speculative resume machinery would not earn its own state.
 */
internal class ProtectionSession(
    private val previouslyAccepted: Boolean,
) {
    private sealed interface State {
        data object Idle : State
        data object SubscribingChallenge : State
        data object AwaitingChallenge : State

        /**
         * One or more challenges answered and awaiting their write callbacks. A list
         * rather than a single value because the cluster has been seen to repeat a
         * challenge before the first response lands, and each repeat is answered in turn.
         */
        data class Responding(val pendingChallenges: List<ByteArray>) : State

        /** Response accepted (or skipped); waiting for the subscription set to prove itself. */
        data class Verifying(val path: ProtectionPath) : State

        data class Ready(val path: ProtectionPath) : State
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
            // Only a fresh pairing can be answering a challenge; see the class comment.
            is State.Responding -> ProtectionPath.ChallengeIndication
            is State.Verifying -> current.path
            is State.Ready -> current.path
            else -> null
        }

    /** Starts the handshake on whichever path [previouslyAccepted] selects. Idempotent. */
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

    /**
     * Handles a challenge indication. An exact repeat of one already being answered is
     * dropped, since answering the same challenge twice would leave a spurious pending
     * write; a *different* challenge arriving mid-flight is answered in turn.
     */
    fun onChallenge(value: ByteArray): ProtectionAction {
        val response = ProtectionHandshake.responseFor(value)
            ?: return unsupportedChallengeFailure()

        return when (val current = state) {
            State.SubscribingChallenge,
            State.AwaitingChallenge,
            -> startResponse(value, response)

            is State.Responding -> if (
                current.pendingChallenges.any { pending -> pending.contentEquals(value) }
            ) {
                ProtectionAction.None
            } else {
                state = State.Responding(current.pendingChallenges + listOf(value.copyOf()))
                ProtectionAction.WriteResponse(response)
            }

            // Our own session bookkeeping is stale, not the bike rejecting us. Retire the link
            // and start a clean handshake; the stored acceptance is still valid.
            State.Idle,
            is State.Verifying,
            is State.Ready,
            -> ProtectionAction.Fail(
                "Authentication challenge arrived outside the handshake; restarting the link",
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
            state = State.Responding(responding.pendingChallenges.drop(1))
            return ProtectionAction.None
        }
        state = State.Verifying(ProtectionPath.ChallengeIndication)
        return ProtectionAction.BeginPostAuthentication
    }

    /**
     * Proof that the subscription set is genuinely live — a notification the cluster only
     * sends over an established session. This, not the CCCD writes completing, is what
     * promotes the session to ready.
     */
    fun onPostAuthenticationEvidence(evidence: String): ProtectionAction = when (val current = state) {
        is State.Verifying -> {
            state = State.Ready(current.path)
            ProtectionAction.CompleteAuthentication(evidence)
        }

        else -> ProtectionAction.None
    }

    /** A required characteristic could not be subscribed: deterministic, so do not retry. */
    fun onRequiredProfileFailure(message: String): ProtectionAction = ProtectionAction.Fail(
        message,
        ProtectionFailurePolicy.Stop,
    )

    fun onVerificationTimeout(): ProtectionAction =
        if (state is State.Verifying) verificationTimeoutFailure() else ProtectionAction.None

    fun onChallengeTimeout(): ProtectionAction = if (state == State.AwaitingChallenge) {
        ProtectionAction.Fail(
            "Motorcycle authentication challenge timed out",
            ProtectionFailurePolicy.Reconnect,
        )
    } else {
        ProtectionAction.None
    }

    private fun startResponse(challenge: ByteArray, response: ByteArray): ProtectionAction {
        state = State.Responding(listOf(challenge.copyOf()))
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
