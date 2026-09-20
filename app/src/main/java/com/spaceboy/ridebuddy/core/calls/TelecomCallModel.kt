package com.spaceboy.ridebuddy.core.calls

import android.telecom.Call

/**
 * One call the cluster is being told about, with the actions the handlebar can take on it.
 *
 * The actions are closures rather than the [Call] itself so the state that reaches the bridge
 * stays free of Telecom types, which keeps the state machine unit-testable and means the
 * bridge never holds a `Call` past the point Telecom has torn it down.
 */
internal data class TrackedCall(
    val id: String,
    val callerName: String?,
    val callerNumber: String?,
    val state: TftCallState,
    val answer: () -> Unit = {},
    val hangUp: () -> Unit = {},
)

/**
 * Cluster call state for a Telecom call state and direction.
 *
 * Null means the call is over, or not yet worth showing: `STATE_NEW` and
 * `STATE_SELECT_PHONE_ACCOUNT` precede a call the rider has any business seeing, and
 * `AUDIO_PROCESSING` is call screening — putting a screened call on the cluster would
 * announce one the dialler has deliberately not announced.
 *
 * Holding maps to answered rather than to its own state: the cluster's vocabulary has three
 * values and a held call is still a call in progress from the rider's point of view.
 */
internal fun tftCallStateForTelecom(callState: Int, incoming: Boolean): TftCallState? = when (callState) {
    Call.STATE_RINGING, Call.STATE_SIMULATED_RINGING -> TftCallState.Ringing
    Call.STATE_DIALING, Call.STATE_CONNECTING, Call.STATE_PULLING_CALL -> TftCallState.Outgoing
    Call.STATE_ACTIVE, Call.STATE_HOLDING ->
        // An outgoing call that connects is still outgoing to the cluster; only an incoming
        // one becomes "answered".
        if (incoming) TftCallState.Answered else TftCallState.Outgoing
    else -> null
}

/**
 * The dialable number from a Telecom handle.
 *
 * Telecom hands back a `tel:` URI for an ordinary call and something else entirely for a SIP
 * or app call, so the scheme is checked rather than assumed. A withheld number arrives as an
 * empty handle, which is "unknown", not a number.
 */
internal fun telecomCallerNumber(scheme: String?, schemeSpecificPart: String?): String? {
    if (!scheme.equals("tel", ignoreCase = true)) return null
    return schemeSpecificPart?.trim()?.takeIf { it.isNotEmpty() }
}
