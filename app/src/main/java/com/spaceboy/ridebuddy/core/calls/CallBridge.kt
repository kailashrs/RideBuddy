package com.spaceboy.ridebuddy.core.calls

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
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
 * Whether a call currently owns the cluster display.
 *
 * Read by [com.spaceboy.ridebuddy.core.tft.TftPriorityCoordinator], which has to know when
 * guidance may take the screen back. Telecom is the authority on the call itself, so there
 * is nothing else worth publishing here.
 */
data class CallIntegrationState(val active: Boolean = false)

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

/** The three call states the cluster can be told about. */
internal enum class TftCallState { Ringing, Answered, Outgoing }

/**
 * Bridges phone calls to the cluster: shows who is calling, and acts on the handlebar
 * answer and decline buttons.
 *
 * Calls arrive from Telecom through [com.spaceboy.ridebuddy.service.RideBuddyInCallService],
 * so this owns only the cluster's side of a call — what to write and when it is safe to.
 * What a call *is* belongs to Telecom, not to this.
 *
 * Outbound writes are gated twice over. Both features are opt-in, and separately, nothing
 * is written until the cluster has shown its side is up — see [armCallWrites]. Writes go
 * through a conflated channel with a generation counter, so a call that changes state
 * faster than the link can carry it sends only the newest state, never a stale one.
 */
class CallBridge(
    context: Context,
    private val bikeConnection: BikeConnection,
    private val appSettings: AppSettingsRepository,
    scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private val mutableState = MutableStateFlow(CallIntegrationState())
    val state: StateFlow<CallIntegrationState> = mutableState.asStateFlow()

    private data class ActiveCallState(
        val callId: String? = null,
        val callerName: String? = null,
        val callerNumber: String? = null,
        val callState: TftCallState = TftCallState.Ringing,
        val answer: () -> Unit = {},
        val hangUp: () -> Unit = {},
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
                    // for the bar — and acting anyway would hang up whatever came next.
                    if (call.callId == null) return@collect
                    when (event.code) {
                        1 -> call.answer()
                        0 -> call.hangUp()
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
                    synchronized(callLock) {
                        clusterAcceptsCallWrites = false
                        nextCallWriteGeneration++
                        while (pendingCallWrites.tryReceive().isSuccess) { /* Discard obsolete transport work. */ }
                    }
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

    /**
     * The call Telecom is reporting, or null when there is none.
     *
     * This is the only way a call reaches the cluster. It is driven by
     * [com.spaceboy.ridebuddy.service.RideBuddyInCallService], so the state is the call's own
     * rather than an inference from which notification a dialler happened to post.
     */
    internal fun onTelecomCallChanged(call: TrackedCall?) {
        val settings = appSettings.settings.value
        synchronized(callLock) {
            applyFeatureSettingsLocked(settings.callFeatureSettings())
            if (call == null) {
                if (activeCall.value.callId != null) clearActiveCallLocked()
                return
            }
            activeCall.value = ActiveCallState(
                callId = call.id,
                // The cluster needs something on the name row to show a call at all. A withheld
                // or unresolved number still gets a call screen rather than nothing.
                callerName = call.callerName ?: call.callerNumber ?: "Unknown caller",
                callerNumber = call.callerNumber,
                callState = call.state,
                answer = call.answer,
                hangUp = call.hangUp,
            )
            if (featureSettings.enabled) publishActiveCallLocked(featureSettings)
            else mutableState.value = CallIntegrationState()
        }
    }

    /** Ends a bike session without replaying an old caller or retaining actionable intents. */
    fun clearPendingBikeOutput() = synchronized(callLock) {
        clusterAcceptsCallWrites = false
        nextCallWriteGeneration++
        while (pendingCallWrites.tryReceive().isSuccess) { /* The channel remains usable for the next session. */ }
        publishedCallActive = false
        activeCall.value = ActiveCallState()
        mutableState.value = CallIntegrationState()
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
            if (bikeConnection.connectionState.value !is BikeConnectionState.Connected) return
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
        if (!clusterAcceptsCallWrites || bikeConnection.connectionState.value !is BikeConnectionState.Connected) return
        nextCallWriteGeneration++
        pendingCallWrites.trySend(CallWriteRequest(nextCallWriteGeneration, writes))
    }

    private fun endedWrite(): BikeWrite = BikeWrite(BleCharacteristics.CallState, TftCallEncoder.ended())

    private fun ActiveCallState.integrationState(): CallIntegrationState =
        CallIntegrationState(active = callId != null)

    private fun AppSettings.callFeatureSettings(): CallFeatureSettings =
        CallFeatureSettings(callerDisplay, tftCallControls)

    /**
     * One conflated unit of outbound call writes. The generation is what lets the drain
     * loop recognise that a newer request has replaced this one mid-sequence.
     */
    private data class CallWriteRequest(val generation: Long, val writes: List<BikeWrite>)

    private companion object {
        const val LogTag = "CallBridge"
    }
}
