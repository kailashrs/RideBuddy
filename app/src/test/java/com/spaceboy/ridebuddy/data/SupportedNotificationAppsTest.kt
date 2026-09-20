package com.spaceboy.ridebuddy.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportedNotificationAppsTest {
    private val SmsCapablePackages = setOf(
        "com.google.android.apps.messaging",
        "com.samsung.android.messaging",
        "com.truecaller",
    )

    @Test
    fun `registry preserves the established TFT event mappings`() {
        assertEquals(
            listOf(
                SupportedNotificationApp("com.whatsapp", "WhatsApp", 6, 7, NotificationAlertCategory.Messages),
                SupportedNotificationApp("com.instagram.android", "Instagram", 12, 13, NotificationAlertCategory.Social),
                SupportedNotificationApp("com.instagram.lite", "Instagram Lite", 12, 13, NotificationAlertCategory.Social),
                SupportedNotificationApp("com.facebook.katana", "Facebook", 10, 11, NotificationAlertCategory.Social),
                SupportedNotificationApp("com.facebook.lite", "Facebook Lite", 10, 11, NotificationAlertCategory.Social),
                SupportedNotificationApp("com.twitter.android", "X", 32, 33, NotificationAlertCategory.Social),
                SupportedNotificationApp("com.google.android.gm", "Gmail", 14, 15, NotificationAlertCategory.Email),
            ),
            SupportedNotificationApps,
        )
    }

    /** No SMS app is named: whichever holds the role is resolved instead. */
    @Test
    fun `the registry names no SMS app`() {
        assertTrue(
            SupportedNotificationApps.none { it.packageName in SmsCapablePackages },
        )
    }

    @Test
    fun `the default SMS app becomes a messages entry on the shared icon`() {
        val app = requireNotNull(defaultSmsNotificationApp("com.truecaller", "Truecaller"))
        assertEquals("com.truecaller", app.packageName)
        assertEquals("Truecaller", app.label)
        assertEquals(NotificationAlertCategory.Messages, app.category)
        assertEquals(7, app.shownEvent)
        assertEquals(6, app.hiddenEvent)
        // A role holder is routinely a dialler too, so its non-message cards stay off.
        assertTrue(app.messagesOnly)
    }

    @Test
    fun `no default SMS app means no entry, and a missing label still names something`() {
        org.junit.Assert.assertNull(defaultSmsNotificationApp(null, null))
        org.junit.Assert.assertNull(defaultSmsNotificationApp("  ", "x"))
        assertTrue(requireNotNull(defaultSmsNotificationApp("com.example.sms", null)).label.isNotBlank())
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

    @Test
    fun `a messages-only app lights its icon for messages alone`() {
        val sms = requireNotNull(defaultSmsNotificationApp("com.truecaller", "Truecaller"))
        assertTrue(sms.acceptsNotification(isMessage = true, isGroupSummary = false))
        // Caller ID, missed-call and promotional cards all arrive as non-messages.
        org.junit.Assert.assertFalse(sms.acceptsNotification(isMessage = false, isGroupSummary = false))
        org.junit.Assert.assertFalse(sms.acceptsNotification(isMessage = true, isGroupSummary = true))
    }

    @Test
    fun `an ordinary messaging app is not restricted to message-shaped notifications`() {
        val whatsapp = SupportedNotificationAppsByPackage.getValue("com.whatsapp")
        org.junit.Assert.assertFalse(whatsapp.messagesOnly)
        assertTrue(whatsapp.acceptsNotification(isMessage = false, isGroupSummary = false))
        org.junit.Assert.assertFalse(whatsapp.acceptsNotification(isMessage = true, isGroupSummary = true))
    }
}
