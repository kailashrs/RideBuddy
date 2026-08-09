package com.spaceboy.ridebuddy.service

import com.spaceboy.ridebuddy.appContainer

import android.content.Context
import android.os.BatteryManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.spaceboy.ridebuddy.AppContainer
import com.spaceboy.ridebuddy.ble.BleCharacteristics
import com.spaceboy.ridebuddy.core.calls.isRideBuddyCallNotification

class BikeNotificationListenerService : NotificationListenerService() {
    private val eventTracker = NotificationEventTracker()

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
                val mapping = EventCodes[notification.packageName] ?: return@mapNotNull null
                if (!mapping.category.enabled(settings) || notification.packageName !in settings.enabledNotificationPackages) {
                    return@mapNotNull null
                }
                mapping.shown to notification.key
            }
        } else {
            emptyList()
        }
        eventTracker.reconcile(eligibleKeys).forEach { event ->
            EventCodes.values.firstOrNull { mapping -> mapping.shown == event }?.let { mapping ->
                sendEvent(mapping.hidden)
            }
            container.tftPriorityCoordinator.notificationRemoved(event)
        }

        regularNotifications.forEach(::onNotificationPosted)
    }

    override fun onNotificationPosted(notification: StatusBarNotification) {
        val container = appContainer
        val mapping = EventCodes[notification.packageName]
        if (container.callNotificationBridge.onNotificationPosted(notification)) {
            mapping?.let { removeTrackedEvent(it, notification.key, container) }
            return
        }
        mapping ?: return
        if (!container.tftPriorityCoordinator.canPresentNotification()) return
        val settings = container.appSettings.settings.value
        if (!mapping.category.enabled(settings) || notification.packageName !in settings.enabledNotificationPackages) return

        val shouldShow = eventTracker.posted(mapping.shown, notification.key)
        if (shouldShow) {
            sendEvent(mapping.shown)
            container.tftPriorityCoordinator.notificationPresented(mapping.shown) {
                val shouldExpire = eventTracker.expire(mapping.shown)
                if (shouldExpire) sendEvent(mapping.hidden)
            }
        }
    }

    override fun onNotificationRemoved(notification: StatusBarNotification) {
        val container = appContainer
        if (container.callNotificationBridge.onNotificationRemoved(notification)) return
        val mapping = EventCodes[notification.packageName] ?: return
        removeTrackedEvent(mapping, notification.key, container)
    }

    private fun removeTrackedEvent(
        mapping: EventMapping,
        notificationKey: String,
        container: AppContainer,
    ) {
        val removal = eventTracker.removed(mapping.shown, notificationKey)
        if (removal != null) {
            if (removal.shouldHide) sendEvent(mapping.hidden)
            container.tftPriorityCoordinator.notificationRemoved(mapping.shown)
        }
    }

    private fun sendEvent(event: Int) {
        val battery = (getSystemService(Context.BATTERY_SERVICE) as BatteryManager)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            .coerceIn(0, 100)
        connection().write(
            BleCharacteristics.AppEvent,
            byteArrayOf(11, event.toByte(), battery.toByte(), 0),
        )
    }

    private fun connection() = appContainer.bikeConnection

    private fun AlertCategory.enabled(settings: com.spaceboy.ridebuddy.data.AppSettings): Boolean = when (this) {
        AlertCategory.Messages -> settings.messageAlerts
        AlertCategory.Social -> settings.socialAlerts
        AlertCategory.Email -> settings.emailAlerts
    }

    private companion object {
        data class EventMapping(val hidden: Int, val shown: Int, val category: AlertCategory)
        enum class AlertCategory { Messages, Social, Email }

        val EventCodes = mapOf(
            "com.facebook.katana" to EventMapping(10, 11, AlertCategory.Social),
            "com.facebook.lite" to EventMapping(10, 11, AlertCategory.Social),
            "com.instagram.android" to EventMapping(12, 13, AlertCategory.Social),
            "com.instagram.lite" to EventMapping(12, 13, AlertCategory.Social),
            "com.google.android.gm" to EventMapping(14, 15, AlertCategory.Email),
            "com.microsoft.office.outlook" to EventMapping(14, 15, AlertCategory.Email),
            "com.twitter.android" to EventMapping(32, 33, AlertCategory.Social),
            "com.google.android.apps.messaging" to EventMapping(6, 7, AlertCategory.Messages),
            "com.samsung.android.messaging" to EventMapping(6, 7, AlertCategory.Messages),
            "com.whatsapp" to EventMapping(6, 7, AlertCategory.Messages),
        )
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
