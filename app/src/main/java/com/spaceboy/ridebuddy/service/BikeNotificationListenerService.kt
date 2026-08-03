package com.spaceboy.ridebuddy.service

import android.content.Context
import android.os.BatteryManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.spaceboy.ridebuddy.Rs457Application
import com.spaceboy.ridebuddy.ble.BleCharacteristics

class BikeNotificationListenerService : NotificationListenerService() {
    private val activeKeysByEvent = mutableMapOf<Int, MutableSet<String>>()
    private val displayedEvents = mutableSetOf<Int>()

    override fun onListenerConnected() {
        super.onListenerConnected()
        activeNotifications.orEmpty().forEach { notification ->
            onNotificationPosted(notification)
        }
    }

    override fun onNotificationPosted(notification: StatusBarNotification) {
        if ((application as Rs457Application).container.callNotificationBridge.onNotificationPosted(notification)) return
        val mapping = EventCodes[notification.packageName] ?: return
        val container = (application as Rs457Application).container
        if (!container.tftPriorityCoordinator.canPresentNotification()) return
        val settings = container.appSettings.settings.value
        if (!mapping.category.enabled(settings) || notification.packageName !in settings.enabledNotificationPackages) return

        val shouldShow = synchronized(activeKeysByEvent) {
            val keys = activeKeysByEvent.getOrPut(mapping.shown) { mutableSetOf() }
            keys.add(notification.key)
            displayedEvents.add(mapping.shown)
        }
        if (shouldShow) {
            sendEvent(mapping.shown)
            container.tftPriorityCoordinator.notificationPresented(mapping.shown) {
                val shouldExpire = synchronized(activeKeysByEvent) { displayedEvents.remove(mapping.shown) }
                if (shouldExpire) sendEvent(mapping.hidden)
            }
        }
    }

    override fun onNotificationRemoved(notification: StatusBarNotification) {
        if ((application as Rs457Application).container.callNotificationBridge.onNotificationRemoved(notification)) return
        val mapping = EventCodes[notification.packageName] ?: return
        val shouldHide = synchronized(activeKeysByEvent) {
            val keys = activeKeysByEvent[mapping.shown] ?: return@synchronized null
            if (!keys.remove(notification.key) || keys.isNotEmpty()) return@synchronized null
            activeKeysByEvent.remove(mapping.shown)
            displayedEvents.remove(mapping.shown)
        }
        if (shouldHide != null) {
            if (shouldHide) sendEvent(mapping.hidden)
            (application as Rs457Application).container.tftPriorityCoordinator.notificationRemoved(mapping.shown)
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

    private fun connection() = (application as Rs457Application).container.bikeConnection

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
