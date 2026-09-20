package com.spaceboy.ridebuddy.service

import com.spaceboy.ridebuddy.appContainer

import android.app.Notification
import android.content.pm.PackageManager
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.spaceboy.ridebuddy.AppContainer
import com.spaceboy.ridebuddy.data.acceptsNotification
import com.spaceboy.ridebuddy.data.defaultSmsNotificationApp
import com.spaceboy.ridebuddy.domain.BikeConnectionState
import com.spaceboy.ridebuddy.data.NotificationAlertCategory
import com.spaceboy.ridebuddy.data.SupportedNotificationApp
import com.spaceboy.ridebuddy.data.SupportedNotificationAppsByPackage

/**
 * Turns phone notifications into cluster icons, and hands call notifications to the call
 * bridge.
 *
 * The cluster shows a small fixed set of icons rather than notification text, so several
 * apps map to one icon (see [SupportedNotificationApps]) and what is tracked is the *icon*,
 * not the notification. An icon stays lit while any notification behind it is live, and is
 * cleared when the last one goes — which is what [NotificationEventTracker] exists to work
 * out.
 *
 * The platform starts and stops this service freely and delivers no events while it is
 * disconnected, so removals can be missed entirely. [onListenerConnected] therefore
 * reconciles against the live notification set rather than assuming its state survived.
 *
 * The icon state itself is *not* held here, for the same reason: it is owned by the
 * process-scoped [NotificationIconWriter], which also owns every write to the icon
 * characteristic. This service decides which notifications are eligible; it does not decide
 * what the cluster is showing.
 */
class BikeNotificationListenerService : NotificationListenerService() {

    /**
     * Rebuilds state from the live notification set after a (re)connection.
     *
     * Calls are replayed before ordinary notifications so a call in progress claims the
     * display before anything else can occupy it. Eligibility is computed against current
     * settings and the priority rules, so icons for notifications that are no longer
     * eligible — or that vanished while the listener was down — are cleared rather than
     * left lit on the cluster indefinitely.
     */
    override fun onListenerConnected() {
        super.onListenerConnected()
        val container = appContainer
        refreshDefaultSmsApp()
        val regularNotifications = activeNotifications.orEmpty().toList()
        val settings = container.appSettings.settings.value
        val eligibleKeys = if (container.bikeConnection.connectionState.value is BikeConnectionState.Connected &&
            container.tftPriorityCoordinator.canPresentNotification()) {
            regularNotifications.mapNotNull { notification ->
                val mapping = notificationApp(notification.packageName) ?: return@mapNotNull null
                if (!notification.acceptsIcon(mapping)) return@mapNotNull null
                if (notification.packageName in settings.disabledNotificationPackages) return@mapNotNull null
                mapping.shownEvent to notification.key
            }
        } else {
            emptyList()
        }
        container.notificationIconWriter.reconcile(eligibleKeys).forEach { event ->
            container.tftPriorityCoordinator.notificationRemoved(event)
        }

        regularNotifications.forEach(::onNotificationPosted)
    }

    override fun onNotificationPosted(notification: StatusBarNotification) {
        val container = appContainer
        // Calls no longer come through here at all: Telecom reports them to the call bridge.
        // This service owns one thing, the per-app icon on the cluster.
        val mapping = notificationApp(notification.packageName) ?: return
        if (!notification.acceptsIcon(mapping)) {
            removeTrackedEvent(mapping, notification.key, container)
            return
        }
        if (container.bikeConnection.connectionState.value !is BikeConnectionState.Connected) return
        if (!container.tftPriorityCoordinator.canPresentNotification()) return
        val settings = container.appSettings.settings.value
        if (notification.packageName in settings.disabledNotificationPackages) return

        if (container.notificationIconWriter.posted(mapping.shownEvent, notification.key)) {
            container.tftPriorityCoordinator.notificationPresented(mapping.shownEvent) {
                container.notificationIconWriter.expire(mapping.shownEvent, mapping.hiddenEvent)
            }
        }
    }

    override fun onNotificationRemoved(notification: StatusBarNotification) {
        val container = appContainer
        val mapping = notificationApp(notification.packageName) ?: return
        removeTrackedEvent(mapping, notification.key, container)
    }

    private fun removeTrackedEvent(
        mapping: SupportedNotificationApp,
        notificationKey: String,
        container: AppContainer,
    ) {
        val groupEnded = container.notificationIconWriter
            .removedLast(mapping.shownEvent, mapping.hiddenEvent, notificationKey)
        if (groupEnded) container.tftPriorityCoordinator.notificationRemoved(mapping.shownEvent)
    }


    /**
     * The cluster icon for a package, if it has one.
     *
     * The default SMS app is resolved rather than listed, so it is checked first: an app can
     * hold that role and also appear in the table, and the role is the more specific fact.
     */
    private fun notificationApp(packageName: String): SupportedNotificationApp? =
        defaultSmsApp?.takeIf { it.packageName == packageName }
            ?: SupportedNotificationAppsByPackage[packageName]

    /**
     * Re-read on each listener connection rather than per notification: changing the default
     * SMS app is a deliberate, rare act, and resolving it is a binder call.
     */
    private var defaultSmsApp: SupportedNotificationApp? = null

    private fun refreshDefaultSmsApp() {
        val packageName = runCatching { Telephony.Sms.getDefaultSmsPackage(this) }.getOrNull()
        val label = packageName?.let {
            runCatching {
                packageManager.getApplicationLabel(
                    packageManager.getApplicationInfo(it, PackageManager.ApplicationInfoFlags.of(0)),
                ).toString()
            }.getOrNull()
        }
        defaultSmsApp = defaultSmsNotificationApp(packageName, label)
    }

    private fun StatusBarNotification.acceptsIcon(mapping: SupportedNotificationApp): Boolean =
        mapping.acceptsNotification(
            isMessage = notification.category == Notification.CATEGORY_MESSAGE ||
                notification.extras.containsKey(Notification.EXTRA_MESSAGES),
            isGroupSummary = notification.flags and Notification.FLAG_GROUP_SUMMARY != 0,
        )
}

/** Notification-icon packet: `[0x0B, event, phone battery percent, 0x00]`. */
internal fun appEventPacket(event: Int, batteryPercent: Int): ByteArray =
    byteArrayOf(11, event.toByte(), batteryPercent.coerceIn(0, 100).toByte(), 0)

/**
 * Clears every notification icon at once. Sent when the cluster announces it has come up,
 * since it has no memory of what it was showing and the phone's own view is authoritative.
 */
internal const val ClearAppEventsEvent = 0

/** [NotificationEventTracker.removed] result. Null means the key was not tracked at all. */
internal data class NotificationEventRemoval(val shouldHide: Boolean)

/**
 * Maps live notifications onto cluster icons.
 *
 * Several notifications, and several apps, share one icon event. This tracks the set of
 * notification keys behind each event so the icon is lit on the first and cleared only on
 * the last — a second message arriving must not re-send an already-lit icon, and one of
 * three being dismissed must not clear it.
 *
 * [displayedEvents] is tracked separately from the key sets because an icon can be taken
 * down without its notifications going away: the priority coordinator expires alerts on a
 * timer. Synchronized throughout, since the platform delivers these callbacks on its own
 * threads.
 */
internal class NotificationEventTracker {
    private val activeKeysByEvent = mutableMapOf<Int, MutableSet<String>>()
    private val displayedEvents = mutableSetOf<Int>()

    /** Records a notification and returns whether its icon needs lighting. */
    @Synchronized
    fun posted(event: Int, key: String): Boolean {
        activeKeysByEvent.getOrPut(event) { mutableSetOf() }.add(key)
        return displayedEvents.add(event)
    }

    /**
     * Records a notification going away. Returns null when it was not tracked or others
     * behind the same icon remain, and otherwise whether the icon should now be cleared.
     */
    @Synchronized
    fun removed(event: Int, key: String): NotificationEventRemoval? {
        val keys = activeKeysByEvent[event] ?: return null
        if (!keys.remove(key) || keys.isNotEmpty()) return null
        activeKeysByEvent.remove(event)
        return NotificationEventRemoval(shouldHide = displayedEvents.remove(event))
    }

    /**
     * The icon's display window ended. Returns whether it was lit, so a duplicate expiry
     * does not write a redundant clear. The notifications behind it stay tracked, so a new
     * one arriving is still recognised as part of the same group.
     */
    @Synchronized
    fun expire(event: Int): Boolean = displayedEvents.remove(event)

    /** The events currently lit on the cluster, for replaying them after it restarts. */
    @Synchronized
    fun displayedEvents(): Set<Int> = displayedEvents.toSet()

    /**
     * Drops keys whose removals were missed while the listener was disconnected and reports any
     * displayed event that now has no live notification.
     */
    @Synchronized
    fun reconcile(activeEntries: Collection<Pair<Int, String>>): Set<Int> {
        val activeKeys = activeEntries.groupByTo(mutableMapOf(), Pair<Int, String>::first) { it.second }
        val hiddenEvents = mutableSetOf<Int>()
        val iterator = activeKeysByEvent.iterator()
        while (iterator.hasNext()) {
            val (event, keys) = iterator.next()
            keys.retainAll(activeKeys[event].orEmpty().toSet())
            if (keys.isEmpty()) {
                iterator.remove()
                if (displayedEvents.remove(event)) hiddenEvents += event
            }
        }
        return hiddenEvents
    }
}
