package com.spaceboy.ridebuddy.service

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
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
 * Telecom binds this because the motorcycle is associated with the `COMPANION_DEVICE_WATCH`
 * device profile, whose role grants the `MANAGE_ONGOING_CALLS` app-op. That app-op, or the
 * system-only `CONTROL_INCALL_EXPERIENCE`, is all `InCallController` will accept from a
 * third-party non-UI service. Binding still does not make RideBuddy the default dialler and
 * does not take calls away from whichever app is. See D7.
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

    /** Looked up once per number while calls are live, not on every details change. */
    private val contactNames = mutableMapOf<String, String?>()

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
        if (callbacks.isEmpty()) contactNames.clear()
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
        appContainer.clusterDisplay.onTelecomCallChanged(tracked)
    }

    /**
     * The saved contact's name where there is one. Telecom resolves it itself for apps that
     * can read contacts; the direct lookup covers builds that withhold it from this service.
     * The network-supplied name comes last: in many regions it is empty.
     */
    private fun Call.callerName(number: String?): String? =
        details.contactDisplayName?.trim()?.takeIf(String::isNotEmpty)
            ?: number?.let { contactNames.getOrPut(it) { lookUpContactName(it) } }
            ?: details.callerDisplayName?.trim()?.takeIf(String::isNotEmpty)

    private fun lookUpContactName(number: String): String? {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        return runCatching {
            contentResolver.query(
                Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)),
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull()?.trim()?.takeIf(String::isNotEmpty)
    }

    private fun Call.toTrackedCall(): TrackedCall? {
        val incoming = details.callDirection != Call.Details.DIRECTION_OUTGOING
        val trackedState = tftCallStateForTelecom(details.state, incoming) ?: return null
        val handle = details.handle
        val number = telecomCallerNumber(handle?.scheme, handle?.schemeSpecificPart)
        return TrackedCall(
            // Telecom's own call id is not public API. Creation time plus the handle is stable
            // for the life of a call and distinguishes it from the next one.
            id = "${details.creationTimeMillis}:${handle?.schemeSpecificPart.orEmpty()}",
            callerName = callerName(number),
            callerNumber = number,
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
