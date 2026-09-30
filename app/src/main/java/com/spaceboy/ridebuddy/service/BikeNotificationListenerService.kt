package com.spaceboy.ridebuddy.service

import com.spaceboy.ridebuddy.appContainer

import android.app.Notification
import android.content.pm.PackageManager
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.spaceboy.ridebuddy.data.acceptsNotification
import com.spaceboy.ridebuddy.data.defaultSmsNotificationApp
import com.spaceboy.ridebuddy.data.SupportedNotificationApp
import com.spaceboy.ridebuddy.data.SupportedNotificationAppsByPackage

/**
 * Decides which phone notifications light a cluster icon; [NotificationIcons] owns the icons.
 *
 * The platform starts and stops this service freely and delivers nothing while it is
 * disconnected, so [onListenerConnected] rebuilds the tracked set from the live notifications.
 */
class BikeNotificationListenerService : NotificationListenerService() {
    private val icons get() = appContainer.notificationIcons

    override fun onListenerConnected() {
        super.onListenerConnected()
        refreshDefaultSmsApp()
        icons.reconcile(
            activeNotifications.orEmpty().mapNotNull { notification ->
                notification.eligibleApp()?.let { app -> notification.key to app }
            }.toMap(),
        )
    }

    override fun onNotificationPosted(notification: StatusBarNotification) {
        val app = notification.eligibleApp()
        if (app == null) icons.removed(notification.key) else icons.posted(notification.key, app)
    }

    override fun onNotificationRemoved(notification: StatusBarNotification) {
        icons.removed(notification.key)
    }

    private fun StatusBarNotification.eligibleApp(): SupportedNotificationApp? =
        notificationApp(packageName)?.takeIf { app ->
            app.acceptsNotification(
                isMessage = notification.category == Notification.CATEGORY_MESSAGE ||
                    notification.extras.containsKey(Notification.EXTRA_MESSAGES),
                isGroupSummary = notification.flags and Notification.FLAG_GROUP_SUMMARY != 0,
            )
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
}
