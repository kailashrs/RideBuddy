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
    /**
     * Whether only message-shaped notifications from this app may light its icon.
     *
     * Set for apps whose traffic is mostly not messages. A dialler that also handles SMS
     * posts caller ID, missed-call and promotional cards through the same package, and none
     * of those are a message arriving — but they are indistinguishable by package alone.
     */
    val messagesOnly: Boolean = false,
)

/**
 * The apps the cluster has icons for, other than texts.
 *
 * Adding an entry requires an event number the firmware recognises — an unknown number draws
 * nothing rather than a generic icon, so this is the protocol's vocabulary rather than a list
 * of apps anyone chose to bless.
 *
 * Texts are deliberately absent. Only the app holding Android's default-SMS role receives
 * them, so naming candidates would be both incomplete and redundant; [defaultSmsNotificationApp]
 * resolves whichever app that is at runtime.
 */
internal val SupportedNotificationApps = listOf(
    SupportedNotificationApp("com.whatsapp", "WhatsApp", 6, 7, NotificationAlertCategory.Messages),
    SupportedNotificationApp("com.instagram.android", "Instagram", 12, 13, NotificationAlertCategory.Social),
    SupportedNotificationApp("com.instagram.lite", "Instagram Lite", 12, 13, NotificationAlertCategory.Social),
    SupportedNotificationApp("com.facebook.katana", "Facebook", 10, 11, NotificationAlertCategory.Social),
    SupportedNotificationApp("com.facebook.lite", "Facebook Lite", 10, 11, NotificationAlertCategory.Social),
    SupportedNotificationApp("com.twitter.android", "X", 32, 33, NotificationAlertCategory.Social),
    SupportedNotificationApp("com.google.android.gm", "Gmail", 14, 15, NotificationAlertCategory.Email),
)

/**
 * The rider's default SMS app as a Messages-category entry, or null when there is none.
 *
 * Resolved rather than listed: a dialler that also handles texts is a text app for this
 * purpose, and which one that is belongs to the rider's OS choice, not to a table here.
 *
 * [SupportedNotificationApp.messagesOnly] is set because an app holding this role is
 * routinely more than an SMS client — caller ID, missed-call and promotional cards all
 * arrive through the same package and none of them are a text arriving.
 */
internal fun defaultSmsNotificationApp(
    packageName: String?,
    label: String?,
): SupportedNotificationApp? = packageName?.takeIf { it.isNotBlank() }?.let {
    SupportedNotificationApp(
        packageName = it,
        label = label?.takeIf(String::isNotBlank) ?: "Texts",
        hiddenEvent = 6,
        shownEvent = 7,
        category = NotificationAlertCategory.Messages,
        messagesOnly = true,
    )
}

internal val SupportedNotificationAppsByPackage = SupportedNotificationApps.associateBy { it.packageName }

/**
 * Every listed app is enabled by default; the feature as a whole is what is opt-in.
 *
 * The default SMS app is not in here and carries no per-app toggle: it is whatever the rider
 * has chosen at OS level rather than one option among several, so the Messages category
 * switch governs it on its own.
 */
internal val DefaultNotificationPackages = SupportedNotificationAppsByPackage.keys

/**
 * Whether a notification from this app should light its cluster icon.
 *
 * Group summaries never do: they restate messages already counted individually.
 */
internal fun SupportedNotificationApp.acceptsNotification(
    isMessage: Boolean,
    isGroupSummary: Boolean,
): Boolean = !isGroupSummary && (!messagesOnly || isMessage)
