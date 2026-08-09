package com.spaceboy.ridebuddy.core.calls

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Person
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.service.notification.StatusBarNotification
import android.telecom.TelecomManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.spaceboy.ridebuddy.ble.BleCharacteristics
import com.spaceboy.ridebuddy.core.tft.TftCallEncoder
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

data class CallIntegrationState(
    val active: Boolean = false,
    val actionsAvailable: Boolean = false,
    val legacyFallbackAvailable: Boolean = false,
    val providerPackage: String? = null,
)

internal fun shouldPublishCallState(callerDisplay: Boolean, tftCallControls: Boolean): Boolean =
    callerDisplay || tftCallControls

internal fun shouldPublishCallerIdentity(callerDisplay: Boolean): Boolean = callerDisplay

internal fun shouldClearPublishedCall(
    published: Boolean,
    callerDisplay: Boolean,
    tftCallControls: Boolean,
): Boolean = published && !shouldPublishCallState(callerDisplay, tftCallControls)

internal fun canUseLegacyCallFallback(enabled: Boolean, permissionGranted: Boolean): Boolean =
    enabled && permissionGranted

internal fun Notification.isRideBuddyCallNotification(): Boolean =
    category == Notification.CATEGORY_CALL ||
            extras.containsKey(Notification.EXTRA_ANSWER_INTENT) ||
            extras.containsKey(Notification.EXTRA_DECLINE_INTENT) ||
            extras.containsKey(Notification.EXTRA_HANG_UP_INTENT)

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
        val ringing: Boolean = false,
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
    private var featureSettings = appSettings.settings.value.callFeatureSettings()

    init {
        scope.launch {
            bikeConnection.controls.collect { event ->
                if (event is BikeControlEvent.CallAction) {
                    if (!appSettings.settings.value.tftCallControls) return@collect
                    val call = synchronized(callLock) { activeCall.value }
                    when (event.code) {
                        1 -> if (!send(call.answerIntent)) useLegacyTelecom(answer = true)
                        0 -> if (!send(call.declineIntent ?: call.hangUpIntent)) useLegacyTelecom(answer = false)
                    }
                }
            }
        }
        scope.launch {
            bikeConnection.connectionState.collect { connectionState ->
                if (connectionState is BikeConnectionState.Connected) publishActiveCall()
            }
        }
        scope.launch {
            appSettings.settings
                .map { settings -> settings.callFeatureSettings() }
                .distinctUntilChanged()
                .collect { settings -> synchronized(callLock) { applyFeatureSettingsLocked(settings) } }
        }
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
        val intents = notification.extractCallIntents()

        val caller = notification.extras.person(Notification.EXTRA_CALL_PERSON)
        val name = caller?.name?.toString().orEmpty()
            .ifBlank { notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty() }
            .ifBlank { "Unknown caller" }
        val text = notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val candidateNumber = caller?.uri?.removePrefix("tel:").orEmpty().ifBlank { text }
        val number = candidateNumber.filter { it.isDigit() || it == '+' }
            .takeIf { it.count(Char::isDigit) >= 5 }
            .orEmpty()
        val callStyleIncoming = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            notification.extras.getInt(
                Notification.EXTRA_CALL_TYPE,
                Notification.CallStyle.CALL_TYPE_UNKNOWN,
            ) == Notification.CallStyle.CALL_TYPE_INCOMING
        } else false
        synchronized(callLock) {
            applyFeatureSettingsLocked(settings.callFeatureSettings())
            activeCall.value = ActiveCallState(
                notificationKey = sbn.key,
                answerIntent = intents.answer,
                declineIntent = intents.decline,
                hangUpIntent = intents.hangUp,
                callerName = name,
                callerNumber = number.takeIf(String::isNotBlank),
                ringing = callStyleIncoming || intents.answer != null,
                providerPackage = sbn.packageName,
            )
            if (featureSettings.enabled) publishActiveCallLocked(featureSettings)
            else mutableState.value = CallIntegrationState()
        }
        return true
    }

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

    private fun activeCallWrites(call: ActiveCallState, settings: CallFeatureSettings): List<BikeWrite> {
        val name = call.callerName ?: return emptyList()
        return buildList {
            // Each conflated request is self-contained. Resetting first prevents caller identity
            // from a superseded request surviving a rapid settings or notification transition.
            add(endedWrite())
            if (shouldPublishCallerIdentity(settings.callerDisplay)) {
                add(BikeWrite(BleCharacteristics.CallerName, TftCallEncoder.callerName(name)))
                call.callerNumber?.let { number ->
                    add(BikeWrite(BleCharacteristics.CallerNumber, TftCallEncoder.callerNumber(number)))
                }
            }
            add(
                BikeWrite(
                    BleCharacteristics.CallState,
                    if (call.ringing) TftCallEncoder.ringing() else TftCallEncoder.accepted(),
                ),
            )
        }
    }

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

    private fun com.spaceboy.ridebuddy.data.AppSettings.callFeatureSettings(): CallFeatureSettings =
        CallFeatureSettings(callerDisplay, tftCallControls)

    private fun Notification.extractCallIntents(): CallIntents {
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
        return CallIntents(answer, decline, hangUp)
    }

    @Suppress("DEPRECATION")
    private fun Bundle.pendingIntent(key: String): PendingIntent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) getParcelable(key, PendingIntent::class.java)
        else getParcelable(key)

    @Suppress("DEPRECATION")
    private fun Bundle.person(key: String): Person? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) getParcelable(key, Person::class.java)
        else getParcelable(key)

    private data class CallIntents(
        val answer: PendingIntent? = null,
        val decline: PendingIntent? = null,
        val hangUp: PendingIntent? = null,
    )

    private data class CallWriteRequest(val generation: Long, val writes: List<BikeWrite>)

    private companion object {
        const val LogTag = "CallNotificationBridge"
    }
}
