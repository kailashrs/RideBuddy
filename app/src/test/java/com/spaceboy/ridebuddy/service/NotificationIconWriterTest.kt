package com.spaceboy.ridebuddy.service

import com.spaceboy.ridebuddy.data.SupportedNotificationApps
import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationIconWriterTest {
    private val mapping = SupportedNotificationApps.first()
    private val other = SupportedNotificationApps.first { it.shownEvent != mapping.shownEvent }

    private val writes = mutableListOf<ByteArray>()
    private val writer = NotificationIconWriter(batteryPercent = { 55 }, write = { writes += it })

    private fun events() = writes.map { it[1].toInt() and 0xFF }

    @Test
    fun `the first notification behind an icon lights it and the rest do not`() {
        assertEquals(true, writer.posted(mapping.shownEvent, "a"))
        assertEquals(false, writer.posted(mapping.shownEvent, "b"))

        assertEquals(listOf(mapping.shownEvent), events())
    }

    @Test
    fun `the icon is cleared only when the last notification behind it goes`() {
        writer.posted(mapping.shownEvent, "a")
        writer.posted(mapping.shownEvent, "b")
        writes.clear()

        // False while a sibling is still live: the group has not ended, so neither the cluster
        // nor the priority coordinator should hear anything yet.
        assertEquals(false, writer.removedLast(mapping.shownEvent, mapping.hiddenEvent, "a"))
        assertEquals(emptyList<Int>(), events())

        assertEquals(true, writer.removedLast(mapping.shownEvent, mapping.hiddenEvent, "b"))
        assertEquals(listOf(mapping.hiddenEvent), events())
    }

    @Test
    fun `an untracked key is reported the same as a group that has not ended`() {
        assertEquals(false, writer.removedLast(mapping.shownEvent, mapping.hiddenEvent, "never-seen"))
        assertEquals(emptyList<Int>(), events())
    }

    @Test
    fun `a restarted cluster is cleared and then told what is still showing`() {
        writer.posted(mapping.shownEvent, "a")
        writer.posted(other.shownEvent, "b")
        writes.clear()

        writer.clearAndReplay()

        // The clear comes first — the cluster is showing whatever it powered up with — and the
        // live icons are re-lit behind it rather than staying dark until their notifications
        // happen to be dismissed and reposted.
        assertEquals(listOf(ClearAppEventsEvent, mapping.shownEvent, other.shownEvent), events())
    }

    @Test
    fun `replaying does not resurrect an icon whose window already expired`() {
        writer.posted(mapping.shownEvent, "a")
        writer.expire(mapping.shownEvent, mapping.hiddenEvent)
        writes.clear()

        writer.clearAndReplay()

        assertEquals(listOf(ClearAppEventsEvent), events())
    }

    @Test
    fun `an expired icon relights when a new notification joins its group`() {
        writer.posted(mapping.shownEvent, "a")
        writer.expire(mapping.shownEvent, mapping.hiddenEvent)
        writes.clear()

        assertEquals(true, writer.posted(mapping.shownEvent, "b"))
        assertEquals(listOf(mapping.shownEvent), events())
    }

    @Test
    fun `battery rides along in every icon packet`() {
        writer.posted(mapping.shownEvent, "a")
        writer.clearAndReplay()

        assertEquals(listOf(55, 55, 55), writes.map { it[2].toInt() and 0xFF })
    }

    @Test
    fun `reconciling clears icons whose notifications vanished while the listener was down`() {
        writer.posted(mapping.shownEvent, "a")
        writer.posted(other.shownEvent, "b")
        writes.clear()

        val takenDown = writer.reconcile(listOf(other.shownEvent to "b"))

        assertEquals(setOf(mapping.shownEvent), takenDown)
        assertEquals(listOf(mapping.hiddenEvent), events())
    }
}
