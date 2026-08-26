package com.spaceboy.ridebuddy.data

internal enum class NotificationAlertCategory {
    Messages,
    Social,
    Email,
}

internal data class SupportedNotificationApp(
    val packageName: String,
    val label: String,
    val hiddenEvent: Int,
    val shownEvent: Int,
    val category: NotificationAlertCategory,
)

internal val SupportedNotificationApps = listOf(
    SupportedNotificationApp("com.google.android.apps.messaging", "Google Messages", 6, 7, NotificationAlertCategory.Messages),
    SupportedNotificationApp("com.samsung.android.messaging", "Samsung Messages", 6, 7, NotificationAlertCategory.Messages),
    SupportedNotificationApp("com.whatsapp", "WhatsApp", 6, 7, NotificationAlertCategory.Messages),
    SupportedNotificationApp("com.instagram.android", "Instagram", 12, 13, NotificationAlertCategory.Social),
    SupportedNotificationApp("com.instagram.lite", "Instagram Lite", 12, 13, NotificationAlertCategory.Social),
    SupportedNotificationApp("com.facebook.katana", "Facebook", 10, 11, NotificationAlertCategory.Social),
    SupportedNotificationApp("com.facebook.lite", "Facebook Lite", 10, 11, NotificationAlertCategory.Social),
    SupportedNotificationApp("com.twitter.android", "X", 32, 33, NotificationAlertCategory.Social),
    SupportedNotificationApp("com.google.android.gm", "Gmail", 14, 15, NotificationAlertCategory.Email),
    SupportedNotificationApp("com.microsoft.office.outlook", "Outlook", 14, 15, NotificationAlertCategory.Email),
)

internal val SupportedNotificationAppsByPackage = SupportedNotificationApps.associateBy { it.packageName }
internal val DefaultNotificationPackages = SupportedNotificationAppsByPackage.keys
