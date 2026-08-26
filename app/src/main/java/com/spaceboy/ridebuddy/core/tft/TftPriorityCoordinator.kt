package com.spaceboy.ridebuddy.core.tft

import com.spaceboy.ridebuddy.core.calls.CallNotificationBridge
import com.spaceboy.ridebuddy.core.navigation.NavigationFeedRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlin.time.Duration.Companion.milliseconds

private const val ImminentTurnMetres = 500

internal fun tftTurnIsImminent(navigationActive: Boolean, distanceToManeuverMetres: Int?): Boolean =
    navigationActive && (distanceToManeuverMetres ?: Int.MAX_VALUE) <= ImminentTurnMetres

internal fun tftNotificationAllowed(
    callActive: Boolean,
    navigationActive: Boolean,
    distanceToManeuverMetres: Int?,
): Boolean = !callActive && !tftTurnIsImminent(navigationActive, distanceToManeuverMetres)

class TftPriorityCoordinator(
    private val navigationFeed: NavigationFeedRepository,
    private val calls: CallNotificationBridge,
    private val navigationBridge: TftNavigationBridge,
    private val scope: CoroutineScope,
) {
    private val activeAlerts = mutableMapOf<String, ActiveAlert>()
    private var callWasActive = calls.state.value.active

    init {
        scope.launch {
            calls.state.collect { state ->
                if (!callWasActive && state.active) expireAllAlerts()
                if (callWasActive && !state.active) navigationBridge.republishLast()
                callWasActive = state.active
            }
        }
        scope.launch {
            navigationFeed.guidance.collect { guidance ->
                if (tftTurnIsImminent(guidance.active, guidance.distanceToManeuverMetres)) {
                    expireAllAlerts()
                }
            }
        }
    }

    fun canPresentNotification(): Boolean {
        if (synchronized(activeAlerts) { TextAlertKey in activeAlerts }) return false
        return presentationWindowAvailable()
    }

    private fun presentationWindowAvailable(): Boolean {
        val guidance = navigationFeed.guidance.value
        return tftNotificationAllowed(
            callActive = calls.state.value.active,
            navigationActive = guidance.active,
            distanceToManeuverMetres = guidance.distanceToManeuverMetres,
        )
    }

    fun notificationPresented(eventId: Int, onExpire: () -> Unit) {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            delay(AlertDurationMillis.milliseconds)
            expireAlert(notificationKey(eventId))
        }
        var accepted = false
        val previous = synchronized(activeAlerts) {
            if (canPresentNotification()) {
                accepted = true
                activeAlerts.put(notificationKey(eventId), ActiveAlert(job, onExpire))
            } else null
        }
        if (!accepted) {
            job.cancel()
            onExpire()
            return
        }
        previous?.job?.cancel()
        job.start()
    }

    fun notificationRemoved(eventId: Int) {
        synchronized(activeAlerts) { activeAlerts.remove(notificationKey(eventId)) }?.job?.cancel()
        resumeNavigationIfAllowed()
    }

    fun presentTextAlert(message: String): Boolean {
        if (message.isBlank()) return false
        val displaced = synchronized(activeAlerts) {
            activeAlerts.entries
                .filter { it.key != TextAlertKey }
                .map { it.value }
                .also { activeAlerts.keys.removeAll { key -> key != TextAlertKey } }
        }
        displaced.forEach { alert ->
            alert.job.cancel()
            alert.onExpire()
        }
        if (!presentationWindowAvailable()) return false

        val previous = synchronized(activeAlerts) { activeAlerts.remove(TextAlertKey) }
        previous?.job?.cancel()
        if (!navigationBridge.presentTextAlert(message)) {
            if (previous != null) {
                navigationBridge.dismissTextAlert()
                resumeNavigationIfAllowed()
            }
            return false
        }

        val job = scope.launch(start = CoroutineStart.LAZY) {
            delay(AlertDurationMillis.milliseconds)
            expireAlert(TextAlertKey)
        }
        synchronized(activeAlerts) {
            activeAlerts[TextAlertKey] = ActiveAlert(job, navigationBridge::dismissTextAlert)
        }
        job.start()
        return true
    }

    private fun expireAlert(key: String) {
        val alert = synchronized(activeAlerts) { activeAlerts.remove(key) } ?: return
        alert.onExpire()
        resumeNavigationIfAllowed()
    }

    private fun expireAllAlerts() {
        val alerts = synchronized(activeAlerts) {
            activeAlerts.values.toList().also { activeAlerts.clear() }
        }
        if (alerts.isEmpty()) return
        alerts.forEach { alert ->
            alert.job.cancel()
            alert.onExpire()
        }
        resumeNavigationIfAllowed()
    }

    private fun resumeNavigationIfAllowed() {
        if (!calls.state.value.active) navigationBridge.republishLast()
    }

    private fun notificationKey(eventId: Int): String = "notification:$eventId"

    private data class ActiveAlert(val job: Job, val onExpire: () -> Unit)

    private companion object {
        const val AlertDurationMillis = 8_000L
        const val TextAlertKey = "text-alert"
    }
}
