package com.spaceboy.ridebuddy.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationEventTrackerTest {
    @Test
    fun groupedEventStaysVisibleUntilItsLastNotificationIsRemoved() {
        val tracker = NotificationEventTracker()

        assertTrue(tracker.posted(event = 7, key = "first"))
        assertFalse(tracker.posted(event = 7, key = "second"))
        assertNull(tracker.removed(event = 7, key = "first"))
        assertTrue(tracker.removed(event = 7, key = "second")?.shouldHide == true)
    }

    @Test
    fun reconnectReconciliationClearsOnlyGroupsWhoseLiveKeysWereMissed() {
        val tracker = NotificationEventTracker()
        tracker.posted(event = 7, key = "message")
        tracker.posted(event = 13, key = "social")

        val hidden = tracker.reconcile(listOf(13 to "social"))

        assertEquals(setOf(7), hidden)
        assertFalse(tracker.posted(event = 13, key = "social"))
        assertTrue(tracker.posted(event = 7, key = "new-message"))
    }

    @Test
    fun expiredGroupCanBePresentedAgainWhileItsNotificationRemainsActive() {
        val tracker = NotificationEventTracker()
        tracker.posted(event = 7, key = "message")

        assertTrue(tracker.expire(event = 7))
        assertTrue(tracker.posted(event = 7, key = "message"))
    }
}
