package com.spaceboy.ridebuddy.service

import android.telecom.Call
import android.telecom.InCallService
import android.telecom.VideoProfile
import com.spaceboy.ridebuddy.appContainer
import com.spaceboy.ridebuddy.core.calls.TftCallState
import com.spaceboy.ridebuddy.core.calls.TrackedCall
import com.spaceboy.ridebuddy.core.calls.telecomCallerNumber
import com.spaceboy.ridebuddy.core.calls.tftCallStateForTelecom

/**
 * The call source for the cluster.
 *
 * Telecom binds this because the app holds `CALL_COMPANION_APP`, which is what the platform
 * offers a companion device rather than a dialler — it does not make RideBuddy the default
 * dialler and does not take calls away from whichever app is.
 *
 * This replaces reading calls out of notifications, which could not be made correct. A
 * dialler is free to post a ringing notification under one id and a separate in-call
 * notification under another, and Truecaller does exactly that: answering removed the
 * notification the bridge was tracking, which is indistinguishable from the call ending, so
 * the cluster said "call ended" at the moment the rider answered. Telecom reports the call
 * itself, so the state is the call's rather than a guess about what a notification meant.
 */
class RideBuddyInCallService : InCallService() {
    private val callbacks = mutableMapOf<Call, Call.Callback>()

    override fun onCallAdded(call: Call) {
        val callback = object : Call.Callback() {
            override fun onStateChanged(changed: Call, state: Int) = publish()
            override fun onDetailsChanged(changed: Call, details: Call.Details) = publish()
        }
        callbacks[call] = callback
        call.registerCallback(callback)
        publish()
    }

    override fun onCallRemoved(call: Call) {
        callbacks.remove(call)?.let(call::unregisterCallback)
        publish()
    }

    override fun onDestroy() {
        // Telecom can unbind with calls still live; leaving callbacks registered would leak
        // them onto a service instance that is going away.
        callbacks.forEach { (call, callback) -> call.unregisterCallback(callback) }
        callbacks.clear()
        super.onDestroy()
    }

    /**
     * Publishes the one call worth showing.
     *
     * A ringing call outranks one already in progress: it is the one the rider has to decide
     * about, and the only one the handlebar can usefully act on.
     */
    private fun publish() {
        val tracked = calls.orEmpty()
            .mapNotNull { call -> call.toTrackedCall() }
            .minByOrNull { if (it.state == TftCallState.Ringing) 0 else 1 }
        appContainer.callBridge.onTelecomCallChanged(tracked)
    }

    private fun Call.toTrackedCall(): TrackedCall? {
        val incoming = details.callDirection != Call.Details.DIRECTION_OUTGOING
        val trackedState = tftCallStateForTelecom(details.state, incoming) ?: return null
        val handle = details.handle
        return TrackedCall(
            // Telecom's own call id is not public API. Creation time plus the handle is stable
            // for the life of a call and distinguishes it from the next one.
            id = "${details.creationTimeMillis}:${handle?.schemeSpecificPart.orEmpty()}",
            callerName = details.callerDisplayName?.trim()?.takeIf { it.isNotEmpty() },
            callerNumber = telecomCallerNumber(handle?.scheme, handle?.schemeSpecificPart),
            state = trackedState,
            answer = { runCatching { answer(VideoProfile.STATE_AUDIO_ONLY) } },
            // reject() is only legal while ringing; disconnect() is what "end this call"
            // means once it is up.
            hangUp = {
                runCatching {
                    // Re-read at press time: the call may have been answered on the
                    // phone since this was built.
                    if (details.state == Call.STATE_RINGING) reject(false, null) else disconnect()
                }
            },
        )
    }
}
