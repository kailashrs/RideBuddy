package com.spaceboy.ridebuddy.ui

import androidx.annotation.StringRes
import com.spaceboy.ridebuddy.R
import com.spaceboy.ridebuddy.domain.ProtectionPath
import com.spaceboy.ridebuddy.domain.ProtectionPhase

// Maps the handshake's domain states onto display strings. Kept in the UI layer so the
// protection state machine stays free of Android resources and remains a pure unit.

@StringRes
internal fun ProtectionPhase.labelResource(): Int = when (this) {
    ProtectionPhase.Idle -> R.string.protection_phase_idle
    ProtectionPhase.SubscribingChallenge -> R.string.protection_phase_subscribing
    ProtectionPhase.AwaitingChallenge -> R.string.protection_phase_awaiting
    ProtectionPhase.Responding -> R.string.protection_phase_responding
    ProtectionPhase.Verifying -> R.string.protection_phase_verifying
    ProtectionPhase.Ready -> R.string.protection_phase_ready
}

@StringRes
internal fun ProtectionPath.labelResource(): Int = when (this) {
    ProtectionPath.StoredAcceptance -> R.string.protection_path_stored_acceptance
    ProtectionPath.ChallengeIndication -> R.string.protection_path_challenge_indication
}
