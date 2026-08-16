package com.spaceboy.ridebuddy.ble

import com.spaceboy.ridebuddy.domain.ProtectionPath
import com.spaceboy.ridebuddy.domain.ProtectionPhase

internal enum class ProtectionFailurePolicy {
    Stop,
    ClearAcceptance,
    ClearAcceptanceAndReconnect,
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

    fun onChallengeSubscriptionReady(): ProtectionAction = when (state) {
        State.SubscribingChallenge -> {
            state = State.AwaitingChallenge
            ProtectionAction.None
        }

        // The indication can arrive before Android reports the successful CCCD write.
        is State.Responding -> ProtectionAction.None
        else -> ProtectionAction.None
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

            State.Idle -> ProtectionAction.Fail(
                "Bike sent an authentication challenge before the session began",
                ProtectionFailurePolicy.ClearAcceptance,
            )
        }
    }

    fun onProtectionResponseWritten(): ProtectionAction {
        val responding = state as? State.Responding ?: return ProtectionAction.Fail(
            "Authentication response completed without a pending challenge",
            ProtectionFailurePolicy.ClearAcceptance,
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

        is State.Responding -> if (
            current.resume is Resume.ContinueVerification && current.deferredEvidence == null
        ) {
            state = current.copy(deferredEvidence = evidence)
            ProtectionAction.None
        } else {
            ProtectionAction.None
        }

        else -> ProtectionAction.None
    }

    fun onRequiredProfileFailure(message: String): ProtectionAction = ProtectionAction.Fail(
        message,
        ProtectionFailurePolicy.ClearAcceptanceAndReconnect,
    )

    fun onVerificationTimeout(): ProtectionAction = when (val current = state) {
        is State.Verifying -> verificationTimeoutFailure()
        is State.Responding -> if (
            current.resume is Resume.ContinueVerification && current.deferredEvidence == null
        ) {
            verificationTimeoutFailure()
        } else {
            ProtectionAction.None
        }

        else -> ProtectionAction.None
    }

    fun onChallengeTimeout(): ProtectionAction = if (state == State.AwaitingChallenge) {
        ProtectionAction.Fail(
            "Motorcycle authentication challenge timed out",
            ProtectionFailurePolicy.ClearAcceptanceAndReconnect,
        )
    } else {
        ProtectionAction.None
    }

    private fun startResponse(
        challenge: ByteArray,
        response: ByteArray,
        resume: Resume,
    ): ProtectionAction {
        state = State.Responding(listOf(challenge.copyOf()), resume)
        return ProtectionAction.WriteResponse(response)
    }

    private fun unsupportedChallengeFailure(): ProtectionAction = ProtectionAction.Fail(
        "Bike sent an unsupported authentication challenge",
        when (state) {
            is State.Verifying,
            is State.Ready,
            is State.Responding,
            -> ProtectionFailurePolicy.ClearAcceptanceAndReconnect

            else -> ProtectionFailurePolicy.ClearAcceptance
        },
    )

    private fun verificationTimeoutFailure(): ProtectionAction = ProtectionAction.Fail(
        "Protected session verification timed out",
        ProtectionFailurePolicy.ClearAcceptanceAndReconnect,
    )
}
