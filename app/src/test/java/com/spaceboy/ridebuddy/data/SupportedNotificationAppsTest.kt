package com.spaceboy.ridebuddy.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportedNotificationAppsTest {
    @Test
    fun `registry preserves the established TFT event mappings`() {
        assertEquals(
            listOf(
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
            ),
            SupportedNotificationApps,
        )
    }

    @Test
    fun `packages are unique and define complete display metadata`() {
        assertEquals(
            SupportedNotificationApps.size,
            SupportedNotificationApps.map { it.packageName }.toSet().size,
        )
        assertTrue(SupportedNotificationApps.all { app ->
            app.packageName.isNotBlank() && app.label.isNotBlank()
        })
    }

    @Test
    fun `apps sharing a shown event also share its hide event and category`() {
        SupportedNotificationApps.groupBy { it.shownEvent }.values.forEach { apps ->
            assertEquals(1, apps.map { it.hiddenEvent }.toSet().size)
            assertEquals(1, apps.map { it.category }.toSet().size)
        }
    }

    @Test
    fun `SupportedNotificationAppsByPackage contains every registry entry`() {
        assertEquals(
            "associateBy must not drop entries — check for duplicate packageName values",
            SupportedNotificationApps.size,
            SupportedNotificationAppsByPackage.size,
        )
    }

    @Test
    fun `all event codes are positive and hidden differs from shown`() {
        assertTrue(SupportedNotificationApps.all { app ->
            app.hiddenEvent > 0 && app.shownEvent > 0 && app.hiddenEvent != app.shownEvent
        })
    }
}
