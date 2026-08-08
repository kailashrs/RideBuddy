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

internal fun canUseLegacyCallFallback(enabled: Boolean, permissionGranted: Boolean): Boolean =
    enabled && permissionGranted

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
    )

    private val activeCall = MutableStateFlow(ActiveCallState())
    private val pendingCallWrites = Channel<CallWriteRequest>(Channel.CONFLATED)
    private val callLock = Any()
    private var nextCallWriteGeneration = 0L

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
            for (request in pendingCallWrites) {
                for (write in request.writes) {
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
        if (notification.category != Notification.CATEGORY_CALL && !notification.hasCallStyleExtras()) return false
        val settings = appSettings.settings.value
        if (!shouldPublishCallState(settings.callerDisplay, settings.tftCallControls)) return true
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
            activeCall.value = ActiveCallState(
                notificationKey = sbn.key,
                answerIntent = intents.answer,
                declineIntent = intents.decline,
                hangUpIntent = intents.hangUp,
                callerName = name,
                callerNumber = number.takeIf(String::isNotBlank),
                ringing = callStyleIncoming || intents.answer != null,
            )
            publishActiveCallLocked()
            mutableState.value = CallIntegrationState(
                active = true,
                actionsAvailable = intents.answer != null || intents.decline != null || intents.hangUp != null,
                legacyFallbackAvailable = legacyTelecomAvailable(),
                providerPackage = sbn.packageName,
            )
        }
        return true
    }

    fun onNotificationRemoved(sbn: StatusBarNotification): Boolean {
        synchronized(callLock) {
            val call = activeCall.value
            if (sbn.key != call.notificationKey) return sbn.notification.category == Notification.CATEGORY_CALL
            val settings = appSettings.settings.value
            if (shouldPublishCallState(settings.callerDisplay, settings.tftCallControls)) {
                enqueueCallWrites(listOf(BikeWrite(BleCharacteristics.CallState, TftCallEncoder.ended())))
            }
            activeCall.value = ActiveCallState()
            mutableState.value = CallIntegrationState()
            return true
        }
    }

    private fun send(intent: PendingIntent?): Boolean {
        if (intent == null) return false
        return runCatching { intent.send() }.isSuccess
    }

    private fun publishActiveCall() {
        synchronized(callLock) { publishActiveCallLocked() }
    }

    private fun publishActiveCallLocked() {
        val settings = appSettings.settings.value
        if (!shouldPublishCallState(settings.callerDisplay, settings.tftCallControls)) return
        val call = activeCall.value
        val name = call.callerName ?: return
        val writes = buildList {
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
        enqueueCallWrites(writes)
    }

    private fun enqueueCallWrites(writes: List<BikeWrite>) {
        nextCallWriteGeneration++
        pendingCallWrites.trySend(CallWriteRequest(nextCallWriteGeneration, writes))
    }

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

    private fun Notification.hasCallStyleExtras(): Boolean =
        extras.containsKey(Notification.EXTRA_ANSWER_INTENT) ||
                extras.containsKey(Notification.EXTRA_DECLINE_INTENT) ||
                extras.containsKey(Notification.EXTRA_HANG_UP_INTENT)

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
