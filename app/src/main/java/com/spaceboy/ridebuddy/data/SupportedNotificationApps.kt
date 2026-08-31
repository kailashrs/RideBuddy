package com.spaceboy.ridebuddy.data

/** Grouping for the settings screen, so apps are not listed as one flat set. */
internal enum class NotificationAlertCategory {
    Messages,
    Social,
    Email,
}

/**
 * One app whose notifications the cluster can display an icon for.
 *
 * The display has a fixed icon set rather than arbitrary graphics, so each app maps to a
 * pair of event numbers the firmware already has artwork for: [shownEvent] draws the icon
 * and [hiddenEvent] removes it. Apps sharing a category share their numbers, because the
 * icon set distinguishes kinds of notification rather than individual apps.
 */
internal data class SupportedNotificationApp(
    val packageName: String,
    val label: String,
    val hiddenEvent: Int,
    val shownEvent: Int,
    val category: NotificationAlertCategory,
)

/**
 * The apps the cluster has icons for. Adding an entry requires an event number the
 * firmware recognises — an unknown number draws nothing rather than a generic icon.
 */
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

/** Every supported app is enabled by default; the feature as a whole is what is opt-in. */
internal val DefaultNotificationPackages = SupportedNotificationAppsByPackage.keys
