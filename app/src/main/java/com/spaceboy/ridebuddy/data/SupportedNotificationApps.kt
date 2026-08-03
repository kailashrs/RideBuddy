package com.spaceboy.ridebuddy.data

data class SupportedNotificationApp(val packageName: String, val label: String)

val SupportedNotificationApps = listOf(
    SupportedNotificationApp("com.google.android.apps.messaging", "Google Messages"),
    SupportedNotificationApp("com.samsung.android.messaging", "Samsung Messages"),
    SupportedNotificationApp("com.whatsapp", "WhatsApp"),
    SupportedNotificationApp("com.instagram.android", "Instagram"),
    SupportedNotificationApp("com.instagram.lite", "Instagram Lite"),
    SupportedNotificationApp("com.facebook.katana", "Facebook"),
    SupportedNotificationApp("com.facebook.lite", "Facebook Lite"),
    SupportedNotificationApp("com.twitter.android", "X"),
    SupportedNotificationApp("com.google.android.gm", "Gmail"),
    SupportedNotificationApp("com.microsoft.office.outlook", "Outlook"),
)

val DefaultNotificationPackages = SupportedNotificationApps.map { it.packageName }.toSet()
