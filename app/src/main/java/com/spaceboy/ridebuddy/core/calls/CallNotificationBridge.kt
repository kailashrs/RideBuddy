package com.spaceboy.ridebuddy.core.calls

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Person
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.service.notification.StatusBarNotification
import android.telecom.TelecomManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.spaceboy.ridebuddy.ble.BleCharacteristics
import com.spaceboy.ridebuddy.core.tft.TftCallEncoder
import com.spaceboy.ridebuddy.data.AppSettings
import com.spaceboy.ridebuddy.data.AppSettingsRepository
import com.spaceboy.ridebuddy.domain.BikeConnection
import com.spaceboy.ridebuddy.domain.BikeControlEvent
import com.spaceboy.ridebuddy.domain.BikeConnectionState
import com.spaceboy.ridebuddy.domain.BikeWrite
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * What the UI shows about call integration.
 *
 * [actionsAvailable] is false when the dialler in use publishes no usable answer or
 * decline control, which is worth surfacing: the handlebar buttons will do nothing, and
 * that looks like a bug rather than a limitation of the phone app.
 */
data class CallIntegrationState(
    val active: Boolean = false,
    val actionsAvailable: Boolean = false,
    val legacyFallbackAvailable: Boolean = false,
    val providerPackage: String? = null,
)

/**
 * Either opt-in feature needs the call state on the cluster: showing the caller needs it
 * to draw the screen, and handlebar controls need it because the cluster only acts on a
 * button press while it believes a call is up.
 */
internal fun shouldPublishCallState(callerDisplay: Boolean, tftCallControls: Boolean): Boolean =
    callerDisplay || tftCallControls

/**
 * True when both features have just been turned off while a call is on the display. It has
 * to be explicitly ended, or the cluster keeps showing it indefinitely.
 */
internal fun shouldClearPublishedCall(
    published: Boolean,
    callerDisplay: Boolean,
    tftCallControls: Boolean,
): Boolean = published && !shouldPublishCallState(callerDisplay, tftCallControls)

/** The deprecated Telecom path is opt-in *and* permission-gated; neither alone is enough. */
internal fun canUseLegacyCallFallback(enabled: Boolean, permissionGranted: Boolean): Boolean =
    enabled && permissionGranted

/** The three call states the cluster can be told about. */
internal enum class TftCallState { Ringing, Answered, Outgoing }

/**
 * Android publishes an answered incoming call and an outgoing call identically, both as
 * `CALL_TYPE_ONGOING`, so the notification alone cannot separate them. What does is whether this
 * key was already on record as ringing: a call that rang here was incoming, and one first seen
 * already in progress was dialled from this phone.
 *
 * A call already running when the listener connects is therefore reported as outgoing. That is
 * unknowable from the notification and only changes which of two states the cluster shows for a
 * call that is already up.
 */
internal fun tftCallStateFor(
    callStyleIncoming: Boolean,
    hasAnswerIntent: Boolean,
    keyWasAlreadyTracked: Boolean,
): TftCallState = when {
    callStyleIncoming || hasAnswerIntent -> TftCallState.Ringing
    keyWasAlreadyTracked -> TftCallState.Answered
    else -> TftCallState.Outgoing
}

internal fun Notification.isRideBuddyCallNotification(): Boolean =
    category == Notification.CATEGORY_CALL ||
            extras.containsKey(Notification.EXTRA_ANSWER_INTENT) ||
            extras.containsKey(Notification.EXTRA_DECLINE_INTENT) ||
            extras.containsKey(Notification.EXTRA_HANG_UP_INTENT)

/**
 * Bridges phone calls to the cluster: shows who is calling, and acts on the handlebar
 * answer and decline buttons.
 *
 * Calls are observed through notifications rather than through Telecom, because reading
 * call state directly would make this app the default dialler. The consequence is that
 * every fact about a call has to be recovered from a notification: the caller's name and
 * number from its extras, and the answer/decline actions from its `CallStyle` intents,
 * falling back to matching action labels for diallers that publish none.
 *
 * Outbound writes are gated twice over. Both features are opt-in, and separately, nothing
 * is written until the cluster has shown its side is up — see [armCallWrites]. Writes go
 * through a conflated channel with a generation counter, so a call that changes state
 * faster than the link can carry it sends only the newest state, never a stale one.
 */
class CallNotificationBridge(
    context: Context,
    private val bikeConnection: BikeConnection,
    private val appSettings: AppSettingsRepository,
    scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private val telecomManager = appContext.getSystemService(TelecomManager::class.java)
    private val mutableState = MutableStateFlow(CallIntegrationState())
    val state: StateFlow<CallIntegrationState> = mutableState.asStateFlow()

    private data class ActiveCallState(
        val notificationKey: String? = null,
        val answerIntent: PendingIntent? = null,
        val declineIntent: PendingIntent? = null,
        val hangUpIntent: PendingIntent? = null,
        val callerName: String? = null,
        val callerNumber: String? = null,
        val callState: TftCallState = TftCallState.Ringing,
        val providerPackage: String? = null,
    )

    private data class CallFeatureSettings(
        val callerDisplay: Boolean,
        val tftCallControls: Boolean,
    ) {
        val enabled: Boolean
            get() = shouldPublishCallState(callerDisplay, tftCallControls)
    }

    private val activeCall = MutableStateFlow(ActiveCallState())
    private val pendingCallWrites = Channel<CallWriteRequest>(Channel.CONFLATED)
    private val callLock = Any()
    private var nextCallWriteGeneration = 0L
    private var publishedCallActive = false
    private var clusterAcceptsCallWrites = false
    private var featureSettings = appSettings.settings.value.callFeatureSettings()

    init {
        scope.launch {
            bikeConnection.controls.collect { event ->
                // A cluster that has just come up has forgotten the call it was showing.
                if (event is BikeControlEvent.ClusterReady) armCallWrites(republish = true)
                // The cluster believes it is in a call. Republishing is what reconciles the two
                // views: the phone's notification is the authority on whether one is really up.
                if (event is BikeControlEvent.ClusterCallActive) armCallWrites(republish = true)
                if (event is BikeControlEvent.CallAction) {
                    if (!appSettings.settings.value.tftCallControls) return@collect
                    val call = synchronized(callLock) { activeCall.value }
                    // Act on a handlebar press only while a call is actually tracked here. A
                    // press without one is stale — the call ended just as the rider reached
                    // for the bar — and falling through to Telecom anyway would hang up
                    // whatever call came next.
                    if (call.notificationKey == null) return@collect
                    when (event.code) {
                        1 -> if (!send(call.answerIntent)) useLegacyTelecom(answer = true)
                        0 -> if (!send(call.declineIntent ?: call.hangUpIntent)) useLegacyTelecom(answer = false)
                    }
                }
            }
        }
        scope.launch {
            bikeConnection.connectionState.collect { connectionState ->
                if (connectionState is BikeConnectionState.Connected) {
                    publishActiveCall()
                } else {
                    // A cluster that has gone away has to show its side is up again before it is
                    // worth writing a call to.
                    synchronized(callLock) { clusterAcceptsCallWrites = false }
                }
            }
        }
        scope.launch {
            // The second of the two readiness signals: whichever lands first, the cluster
            // announcing itself or the first telemetry frame, arms the call writes.
            bikeConnection.telemetry.collect { frame ->
                if (frame != null) armCallWrites(republish = false)
            }
        }
        scope.launch {
            appSettings.settings
                .map { settings -> settings.callFeatureSettings() }
                .distinctUntilChanged()
                .collect { settings -> synchronized(callLock) { applyFeatureSettingsLocked(settings) } }
        }
        // Drains call writes one request at a time. The generation is rechecked between
        // writes so a superseded request stops partway rather than finishing and leaving
        // the cluster on a state the phone has already moved past.
        scope.launch {
            for (request in pendingCallWrites) {
                for (write in request.writes) {
                    val currentGeneration = synchronized(callLock) { nextCallWriteGeneration }
                    if (request.generation != currentGeneration) break
                    if (bikeConnection.writeAndAwait(write)) continue
                    Log.w(LogTag, "Call packet rejected for ${write.characteristic}")
                    break
                }
            }
        }
    }

    /** Returns true when this is a call notification and should not be handled as a normal app alert. */
    fun onNotificationPosted(sbn: StatusBarNotification): Boolean {
        val notification = sbn.notification
        if (!notification.isRideBuddyCallNotification()) return false
        val settings = appSettings.settings.value
        val intents = notification.extractCallIntents(sbn.packageName)

        val caller = notification.extras.person(Notification.EXTRA_CALL_PERSON)
        val name = caller?.name?.toString().orEmpty()
            .ifBlank { notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty() }
            .ifBlank { "Unknown caller" }
        val text = notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val candidateNumber = caller?.uri?.removePrefix("tel:").orEmpty().ifBlank { text }
        val number = candidateNumber.filter { it.isDigit() || it == '+' }
            .takeIf { it.count(Char::isDigit) >= 5 }
            .orEmpty()
        val callStyleIncoming = notification.extras.getInt(
            Notification.EXTRA_CALL_TYPE,
            Notification.CallStyle.CALL_TYPE_UNKNOWN,
        ) == Notification.CallStyle.CALL_TYPE_INCOMING
        synchronized(callLock) {
            applyFeatureSettingsLocked(settings.callFeatureSettings())
            val callState = tftCallStateFor(
                callStyleIncoming = callStyleIncoming,
                hasAnswerIntent = intents.answer != null,
                keyWasAlreadyTracked = activeCall.value.notificationKey == sbn.key,
            )
            activeCall.value = ActiveCallState(
                notificationKey = sbn.key,
                answerIntent = intents.answer,
                declineIntent = intents.decline,
                hangUpIntent = intents.hangUp,
                callerName = name,
                callerNumber = number.takeIf(String::isNotBlank),
                callState = callState,
                providerPackage = sbn.packageName,
            )
            if (featureSettings.enabled) publishActiveCallLocked(featureSettings)
            else mutableState.value = CallIntegrationState()
        }
        return true
    }

    /**
     * Handles a notification going away. Returns true when it was a call notification, so
     * the caller does not also treat it as an ordinary app alert being dismissed.
     */
    fun onNotificationRemoved(sbn: StatusBarNotification): Boolean {
        synchronized(callLock) {
            val call = activeCall.value
            if (sbn.key != call.notificationKey) return sbn.notification.isRideBuddyCallNotification()
            clearActiveCallLocked()
            return true
        }
    }

    /** Reconciles removals Android could not deliver while the notification listener was offline. */
    fun reconcileActiveNotifications(notifications: Collection<StatusBarNotification>) {
        val activeCallKeys = notifications.asSequence()
            .filter { notification -> notification.notification.isRideBuddyCallNotification() }
            .map { notification -> notification.key }
            .toSet()
        synchronized(callLock) {
            val activeKey = activeCall.value.notificationKey ?: return
            if (activeKey !in activeCallKeys) clearActiveCallLocked()
        }
    }

    private fun send(intent: PendingIntent?): Boolean {
        if (intent == null) return false
        return runCatching { intent.send() }.isSuccess
    }

    /**
     * Marks the cluster as ready to receive call writes.
     *
     * Nothing about a call is written until the cluster has shown its own side is up, by
     * either announcing itself or sending telemetry. A write that arrives earlier reaches a
     * cluster that is not yet drawing the call screen, and it is dropped outright rather
     * than deferred — so the rider simply never sees that call.
     *
     * [republish] forces a redraw even when already armed, for the case where the cluster
     * has restarted and forgotten what it was showing.
     */
    private fun armCallWrites(republish: Boolean) {
        val publish = synchronized(callLock) {
            val wasArmed = clusterAcceptsCallWrites
            clusterAcceptsCallWrites = true
            republish || !wasArmed
        }
        if (publish) publishActiveCall()
    }

    private fun publishActiveCall() {
        synchronized(callLock) {
            applyFeatureSettingsLocked(appSettings.settings.value.callFeatureSettings())
            if (featureSettings.enabled) publishActiveCallLocked(featureSettings)
        }
    }

    private fun publishActiveCallLocked(settings: CallFeatureSettings) {
        if (!settings.enabled) return
        val call = activeCall.value
        if (call.callerName == null) return
        enqueueCallWrites(activeCallWrites(call, settings))
        publishedCallActive = true
        mutableState.value = call.integrationState()
    }

    /**
     * Builds the full write sequence for a call's current state.
     *
     * Caller name and number are only written when the display feature is on; the state
     * itself is written whenever either feature is, because handlebar controls depend on
     * the cluster believing a call is up.
     */
    private fun activeCallWrites(call: ActiveCallState, settings: CallFeatureSettings): List<BikeWrite> {
        val name = call.callerName ?: return emptyList()
        return buildList {
            // Each conflated request is self-contained. Resetting first prevents caller identity
            // from a superseded request surviving a rapid settings or notification transition.
            add(endedWrite())
            if (settings.callerDisplay) {
                add(BikeWrite(BleCharacteristics.CallerName, TftCallEncoder.callerName(name)))
                call.callerNumber?.let { number ->
                    add(BikeWrite(BleCharacteristics.CallerNumber, TftCallEncoder.callerNumber(number)))
                }
            }
            add(
                BikeWrite(
                    BleCharacteristics.CallState,
                    when (call.callState) {
                        TftCallState.Ringing -> TftCallEncoder.ringing()
                        TftCallState.Answered -> TftCallEncoder.accepted()
                        TftCallState.Outgoing -> TftCallEncoder.outgoing()
                    },
                ),
            )
        }
    }

    /**
     * Reacts to the opt-in settings changing mid-call: republishes under the new settings,
     * or explicitly ends the displayed call when both features have been turned off.
     */
    private fun applyFeatureSettingsLocked(settings: CallFeatureSettings) {
        if (settings == featureSettings) return
        featureSettings = settings
        val call = activeCall.value
        if (!settings.enabled) {
            if (shouldClearPublishedCall(
                    published = publishedCallActive,
                    callerDisplay = settings.callerDisplay,
                    tftCallControls = settings.tftCallControls,
                )
            ) {
                enqueueCallWrites(listOf(endedWrite()))
            }
            publishedCallActive = false
            mutableState.value = CallIntegrationState()
            return
        }
        if (call.callerName == null) return

        val writes = activeCallWrites(call, settings)
        enqueueCallWrites(writes)
        publishedCallActive = true
        mutableState.value = call.integrationState()
    }

    private fun clearActiveCallLocked() {
        if (publishedCallActive) enqueueCallWrites(listOf(endedWrite()))
        publishedCallActive = false
        activeCall.value = ActiveCallState()
        mutableState.value = CallIntegrationState()
    }

    private fun enqueueCallWrites(writes: List<BikeWrite>) {
        if (writes.isEmpty()) return
        // Every caller holds callLock, so this reads the armed state without racing it.
        if (!clusterAcceptsCallWrites) return
        nextCallWriteGeneration++
        pendingCallWrites.trySend(CallWriteRequest(nextCallWriteGeneration, writes))
    }

    private fun endedWrite(): BikeWrite = BikeWrite(BleCharacteristics.CallState, TftCallEncoder.ended())

    private fun ActiveCallState.integrationState(): CallIntegrationState = CallIntegrationState(
        active = true,
        actionsAvailable = answerIntent != null || declineIntent != null || hangUpIntent != null,
        legacyFallbackAvailable = legacyTelecomAvailable(),
        providerPackage = providerPackage,
    )

    /**
     * Opt-in fallback for diallers that publish no usable notification actions.
     *
     * Uses deprecated Telecom controls and needs a runtime permission, which is why it is
     * off by default and tried only after the notification intents have failed. It does not
     * make this app the default dialler.
     */
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun useLegacyTelecom(answer: Boolean): Boolean {
        if (!legacyTelecomAvailable()) return false
        return runCatching {
            if (answer) {
                telecomManager.acceptRingingCall()
                true
            } else {
                telecomManager.endCall()
            }
        }.getOrDefault(false)
    }

    private fun legacyTelecomAvailable(): Boolean = canUseLegacyCallFallback(
        enabled = appSettings.settings.value.legacyCallControls,
        permissionGranted = ContextCompat.checkSelfPermission(appContext, Manifest.permission.ANSWER_PHONE_CALLS) ==
                PackageManager.PERMISSION_GRANTED,
    )

    private fun AppSettings.callFeatureSettings(): CallFeatureSettings =
        CallFeatureSettings(callerDisplay, tftCallControls)

    /**
     * Recovers answer, decline and hang-up actions from a call notification.
     *
     * `CallStyle` publishes them as named extras, which is exact and is tried first. A
     * dialler that does not use `CallStyle` leaves only its action buttons, which have to
     * be matched by label — inexact, English-only, and logged when it finds nothing so the
     * gap is visible rather than silent.
     */
    private fun Notification.extractCallIntents(packageName: String): CallIntents {
        val extrasIntents = CallIntents(
            answer = extras.pendingIntent(Notification.EXTRA_ANSWER_INTENT),
            decline = extras.pendingIntent(Notification.EXTRA_DECLINE_INTENT),
            hangUp = extras.pendingIntent(Notification.EXTRA_HANG_UP_INTENT),
        )
        if (extrasIntents.answer != null || extrasIntents.decline != null || extrasIntents.hangUp != null) return extrasIntents

        val availableActions = actions.orEmpty().filter { it.actionIntent != null }
        fun find(vararg words: String): PendingIntent? = availableActions.firstOrNull { action ->
            words.any { word -> action.title?.toString()?.contains(word, ignoreCase = true) == true }
        }?.actionIntent

        val answer = find("answer", "accept", "pick up")
        val decline = find("decline", "reject")
        val hangUp = find("hang up", "end call", "disconnect")
        if (answer == null && decline == null && hangUp == null) {
            // This search is English-only, and there is no locale-independent way to read a
            // non-CallStyle dialer's buttons. Say so rather than looking like a silent no-op:
            // handlebar controls will fall through to the opt-in Telecom path, or do nothing.
            Log.w(
                LogTag,
                "No call actions found for $packageName; it publishes neither CallStyle intents " +
                    "nor recognisable action labels",
            )
        }
        return CallIntents(answer, decline, hangUp)
    }

    private fun Bundle.pendingIntent(key: String): PendingIntent? =
        getParcelable(key, PendingIntent::class.java)

    private fun Bundle.person(key: String): Person? =
        getParcelable(key, Person::class.java)

    private data class CallIntents(
        val answer: PendingIntent? = null,
        val decline: PendingIntent? = null,
        val hangUp: PendingIntent? = null,
    )

    /**
     * One conflated unit of outbound call writes. The generation is what lets the drain
     * loop recognise that a newer request has replaced this one mid-sequence.
     */
    private data class CallWriteRequest(val generation: Long, val writes: List<BikeWrite>)

    private companion object {
        const val LogTag = "CallNotificationBridge"
    }
}
