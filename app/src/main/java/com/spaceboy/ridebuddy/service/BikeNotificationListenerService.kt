package com.spaceboy.ridebuddy.service

import com.spaceboy.ridebuddy.appContainer

import android.content.Context
import android.os.BatteryManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.spaceboy.ridebuddy.AppContainer
import com.spaceboy.ridebuddy.ble.BleCharacteristics
import com.spaceboy.ridebuddy.core.calls.isRideBuddyCallNotification
import com.spaceboy.ridebuddy.data.AppSettings
import com.spaceboy.ridebuddy.data.NotificationAlertCategory
import com.spaceboy.ridebuddy.data.SupportedNotificationApp
import com.spaceboy.ridebuddy.data.SupportedNotificationApps
import com.spaceboy.ridebuddy.data.SupportedNotificationAppsByPackage

class BikeNotificationListenerService : NotificationListenerService() {
    private val eventTracker = NotificationEventTracker()
    private val batteryManager by lazy { getSystemService(Context.BATTERY_SERVICE) as BatteryManager }

    override fun onListenerConnected() {
        super.onListenerConnected()
        val container = appContainer
        val notifications = activeNotifications.orEmpty().toList()
        container.callNotificationBridge.reconcileActiveNotifications(notifications)
        val (callNotifications, regularNotifications) = notifications.partition { notification ->
            notification.notification.isRideBuddyCallNotification()
        }
        callNotifications.forEach(::onNotificationPosted)

        val settings = container.appSettings.settings.value
        val eligibleKeys = if (container.tftPriorityCoordinator.canPresentNotification()) {
            regularNotifications.mapNotNull { notification ->
                val mapping = SupportedNotificationAppsByPackage[notification.packageName] ?: return@mapNotNull null
                if (!mapping.category.enabled(settings) || notification.packageName !in settings.enabledNotificationPackages) {
                    return@mapNotNull null
                }
                mapping.shownEvent to notification.key
            }
        } else {
            emptyList()
        }
        eventTracker.reconcile(eligibleKeys).forEach { event ->
            SupportedNotificationApps.firstOrNull { mapping -> mapping.shownEvent == event }?.let { mapping ->
                sendEvent(mapping.hiddenEvent)
            }
            container.tftPriorityCoordinator.notificationRemoved(event)
        }

        regularNotifications.forEach(::onNotificationPosted)
    }

    override fun onNotificationPosted(notification: StatusBarNotification) {
        val container = appContainer
        val mapping = SupportedNotificationAppsByPackage[notification.packageName]
        if (container.callNotificationBridge.onNotificationPosted(notification)) {
            mapping?.let { removeTrackedEvent(it, notification.key, container) }
            return
        }
        mapping ?: return
        if (!container.tftPriorityCoordinator.canPresentNotification()) return
        val settings = container.appSettings.settings.value
        if (!mapping.category.enabled(settings) || notification.packageName !in settings.enabledNotificationPackages) return

        val shouldShow = eventTracker.posted(mapping.shownEvent, notification.key)
        if (shouldShow) {
            sendEvent(mapping.shownEvent)
            container.tftPriorityCoordinator.notificationPresented(mapping.shownEvent) {
                val shouldExpire = eventTracker.expire(mapping.shownEvent)
                if (shouldExpire) sendEvent(mapping.hiddenEvent)
            }
        }
    }

    override fun onNotificationRemoved(notification: StatusBarNotification) {
        val container = appContainer
        if (container.callNotificationBridge.onNotificationRemoved(notification)) return
        val mapping = SupportedNotificationAppsByPackage[notification.packageName] ?: return
        removeTrackedEvent(mapping, notification.key, container)
    }

    private fun removeTrackedEvent(
        mapping: SupportedNotificationApp,
        notificationKey: String,
        container: AppContainer,
    ) {
        val removal = eventTracker.removed(mapping.shownEvent, notificationKey)
        if (removal != null) {
            if (removal.shouldHide) sendEvent(mapping.hiddenEvent)
            container.tftPriorityCoordinator.notificationRemoved(mapping.shownEvent)
        }
    }

    private fun sendEvent(event: Int) {
        val battery = batteryManager
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            .coerceIn(0, 100)
        appContainer.bikeConnection.enqueueWrite(
            BleCharacteristics.AppEvent,
            byteArrayOf(11, event.toByte(), battery.toByte(), 0),
        )
    }

    private fun NotificationAlertCategory.enabled(settings: AppSettings): Boolean = when (this) {
        NotificationAlertCategory.Messages -> settings.messageAlerts
        NotificationAlertCategory.Social -> settings.socialAlerts
        NotificationAlertCategory.Email -> settings.emailAlerts
    }
}

internal data class NotificationEventRemoval(val shouldHide: Boolean)

/** Tracks grouped notification keys independently of the Android service lifecycle. */
internal class NotificationEventTracker {
    private val activeKeysByEvent = mutableMapOf<Int, MutableSet<String>>()
    private val displayedEvents = mutableSetOf<Int>()

    @Synchronized
    fun posted(event: Int, key: String): Boolean {
        activeKeysByEvent.getOrPut(event) { mutableSetOf() }.add(key)
        return displayedEvents.add(event)
    }

    @Synchronized
    fun removed(event: Int, key: String): NotificationEventRemoval? {
        val keys = activeKeysByEvent[event] ?: return null
        if (!keys.remove(key) || keys.isNotEmpty()) return null
        activeKeysByEvent.remove(event)
        return NotificationEventRemoval(shouldHide = displayedEvents.remove(event))
    }

    @Synchronized
    fun expire(event: Int): Boolean = displayedEvents.remove(event)

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
