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

/** Distance inside which a turn owns the display and nothing may cover it. */
private const val ImminentTurnMetres = 500

/**
 * Whether guidance is close enough to a turn that the rider needs to see it now.
 *
 * An unknown distance is treated as far away rather than imminent. Guidance publishes no
 * distance when it has not resolved one yet, and blocking every alert on that would
 * suppress alerts for as long as the state persisted.
 */
internal fun tftTurnIsImminent(navigationActive: Boolean, distanceToManeuverMetres: Int?): Boolean =
    navigationActive && (distanceToManeuverMetres ?: Int.MAX_VALUE) <= ImminentTurnMetres

/**
 * The display priority rule, in one expression: a live call outranks everything, an
 * imminent turn outranks a notification, and a notification gets whatever is left.
 */
internal fun tftNotificationAllowed(
    callActive: Boolean,
    navigationActive: Boolean,
    distanceToManeuverMetres: Int?,
): Boolean = !callActive && !tftTurnIsImminent(navigationActive, distanceToManeuverMetres)

/**
 * Arbitrates the one display area that calls, guidance and alerts all want.
 *
 * The cluster has a single navigation region and no concept of layering, so anything
 * written there replaces what was showing. This class decides what may be written and, just
 * as importantly, restores guidance once a transient alert is gone — otherwise the rider is
 * left looking at an expired notification where their next turn should be.
 *
 * Every alert is bounded: it either expires on its timer, is displaced by something with a
 * higher claim, or is removed at the source. Each of those paths runs the alert's own
 * `onExpire` exactly once, which is what stops a displaced alert from later expiring and
 * clearing a display it no longer owns.
 *
 * The alert map is shared between coroutine timers and inbound notification callbacks, so
 * it is lock-guarded, and side effects are performed outside the lock.
 */
class TftPriorityCoordinator(
    private val navigationFeed: NavigationFeedRepository,
    private val calls: CallNotificationBridge,
    private val navigationBridge: TftNavigationBridge,
    private val scope: CoroutineScope,
) {
    private val activeAlerts = mutableMapOf<String, ActiveAlert>()
    private var callWasActive = calls.state.value.active

    init {
        // A call arriving takes the display immediately, so outstanding alerts are expired
        // rather than left to time out over the top of it. When it ends, guidance is
        // republished, because the call screen has overwritten whatever was there.
        scope.launch {
            calls.state.collect { state ->
                if (!callWasActive && state.active) expireAllAlerts()
                if (callWasActive && !state.active) navigationBridge.republishLast()
                callWasActive = state.active
            }
        }
        // An alert may have been posted while the next turn was still far off. Approaching
        // it revokes that permission mid-display rather than only at the moment of posting.
        scope.launch {
            navigationFeed.guidance.collect { guidance ->
                if (tftTurnIsImminent(guidance.active, guidance.distanceToManeuverMetres)) {
                    expireAllAlerts()
                }
            }
        }
    }

    /** Whether a notification icon may be shown right now. */
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

    /**
     * Registers a notification that has been put on the display, so it can be cleared again.
     *
     * The timer is created lazily and only started once the alert is actually accepted;
     * building it first keeps the whole accept-or-reject decision inside a single locked
     * section. A rejected alert has its `onExpire` invoked immediately so the caller's
     * cleanup runs on every path.
     */
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

    /** The source notification is gone — drop its alert and give the display back. */
    fun notificationRemoved(eventId: Int) {
        synchronized(activeAlerts) { activeAlerts.remove(notificationKey(eventId)) }?.job?.cancel()
        resumeNavigationIfAllowed()
    }

    /**
     * Puts a line of text on the display, returning whether it was accepted.
     *
     * Text alerts outrank notification icons — they carry a message a glyph cannot — so
     * they displace any that are showing. That displacement happens before the priority
     * check, since the icons are being replaced either by this alert or by guidance
     * resuming. If the write itself fails, an alert that was already up is torn down rather
     * than left half-replaced.
     */
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

    /**
     * Redraws guidance after an alert clears. Skipped during a call, which owns the display
     * outright and would be overwritten.
     */
    private fun resumeNavigationIfAllowed() {
        if (!calls.state.value.active) navigationBridge.republishLast()
    }

    private fun notificationKey(eventId: Int): String = "notification:$eventId"

    private data class ActiveAlert(val job: Job, val onExpire: () -> Unit)

    private companion object {
        /** How long an alert holds the display before guidance takes it back. */
        const val AlertDurationMillis = 8_000L

        /** Fixed key: only one text alert can be up at a time, unlike per-event icons. */
        const val TextAlertKey = "text-alert"
    }
}
