package com.spaceboy.ridebuddy.core.calls

import android.app.Notification
import android.app.PendingIntent
import android.app.Person
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Platform notification fixtures, including Truecaller's legacy category-free shape. */
class CallNotificationParsingTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val action: PendingIntent get() = PendingIntent.getBroadcast(
        context, 907, Intent("com.spaceboy.ridebuddy.TEST_CALL_ACTION").setPackage(context.packageName),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    @Test fun callStyleUsesStructuredIntentsWithoutMatchingTranslatedLabels() {
        val notification = Notification.Builder(context, "test")
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setStyle(Notification.CallStyle.forIncomingCall(
                Person.Builder().setName("Caller").build(), action, action,
            )).build()
        assertTrue(notification.isRideBuddyCallNotification())
    }

    @Test fun defaultDialerLegacyControlsAreRecognizedWithoutCategory() {
        val notification = Notification.Builder(context, "test")
            .addAction(Notification.Action.Builder(null, "Answer", action).build())
            .addAction(Notification.Action.Builder(null, "Decline", action).build())
            .build()
        assertTrue(notification.isRideBuddyCallNotification(isKnownDialer = true))
        assertFalse(notification.isRideBuddyCallNotification(isKnownDialer = false))
    }

    @Test fun missedCallWithCallbackActionDoesNotBecomeLive() {
        val notification = Notification.Builder(context, "test")
            .setCategory(Notification.CATEGORY_CALL)
            .addAction(Notification.Action.Builder(null, "Call back", action).build())
            .build()
        assertFalse(notification.isRideBuddyCallNotification(isKnownDialer = true))
    }
}
