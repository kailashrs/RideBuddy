package com.spaceboy.ridebuddy.service

import com.spaceboy.ridebuddy.data.SupportedNotificationApps

/**
 * The one place cluster notification icons are decided and written.
 *
 * It exists at process scope because the two halves of the problem live in different places.
 * [BikeNotificationListenerService] knows which notifications are live, but the platform starts
 * and stops it freely; the cluster's own "I have come up" announcement arrives on the BLE
 * connection, which outlives the service. Splitting the tracker from the writer meant the clear
 * sent on that announcement blanked every icon on the cluster while the tracker still believed
 * they were lit — so a message that was still live would never light its icon again, because
 * [NotificationEventTracker.posted] reports an already-displayed event as needing no write.
 * Owning both sides makes that impossible to express.
 *
 * Every method holds [lock] across the tracker decision *and* the write it implies. Writes are
 * enqueued in order onto the connection's own queue, so serialising the decisions is enough to
 * guarantee the cluster sees them in the order they were made.
 */
internal class NotificationIconWriter(
    private val batteryPercent: () -> Int,
    private val write: (ByteArray) -> Unit,
) {
    private val lock = Any()
    private val tracker = NotificationEventTracker()

    /** Records a notification, lighting its icon if it was not already lit. */
    fun posted(shownEvent: Int, key: String): Boolean = synchronized(lock) {
        if (!tracker.posted(shownEvent, key)) return false
        writeLocked(shownEvent)
        true
    }

    /**
     * Records a notification going away, clearing its icon once the last one behind it is gone.
     *
     * Returns whether that emptied the group — false both for an untracked key and for one that
     * still has siblings. That is deliberately the same condition the priority coordinator wants:
     * it tracks icons, not notifications, so it should hear about the group ending rather than
     * about each dismissal within it.
     */
    fun removedLast(shownEvent: Int, hiddenEvent: Int, key: String): Boolean = synchronized(lock) {
        val removal = tracker.removed(shownEvent, key) ?: return false
        if (removal.shouldHide) writeLocked(hiddenEvent)
        true
    }

    /** The icon's display window ended: take it down, but keep its notifications tracked. */
    fun expire(shownEvent: Int, hiddenEvent: Int) = synchronized(lock) {
        if (tracker.expire(shownEvent)) writeLocked(hiddenEvent)
    }

    /**
     * Reconciles against the live notification set after the listener reconnects, clearing icons
     * whose notifications went away unobserved. Returns the events that were taken down so the
     * caller can tell the priority coordinator.
     */
    fun reconcile(eligibleEntries: Collection<Pair<Int, String>>): Set<Int> = synchronized(lock) {
        tracker.reconcile(eligibleEntries).onEach { event ->
            SupportedNotificationApps.firstOrNull { mapping -> mapping.shownEvent == event }
                ?.let { mapping -> writeLocked(mapping.hiddenEvent) }
        }
    }

    /**
     * Blanks the cluster's icons and immediately re-lights the ones that should still be showing.
     *
     * Sent when the cluster announces it has come up. It has no memory of what it was displaying,
     * so the phone's view is authoritative — but a bare clear would leave the display disagreeing
     * with that view for as long as those notifications stay live, which for a chat thread the
     * rider has not opened is indefinitely.
     *
     * Clear and replay share one critical section so nothing observes the gap: a notification
     * arriving mid-sequence waits and is written after the replay, rather than being blanked by a
     * clear that had already been decided.
     */
    fun clearAndReplay() = synchronized(lock) {
        val battery = batteryPercent()
        write(appEventPacket(ClearAppEventsEvent, battery))
        tracker.displayedEvents().forEach { event -> write(appEventPacket(event, battery)) }
    }

    /**
     * Writes one icon event. Battery level rides along in the same packet — it is a field of this
     * packet rather than one of its own, so the cluster's battery indicator is only ever updated
     * as a side effect of a notification event.
     */
    private fun writeLocked(event: Int) {
        write(appEventPacket(event, batteryPercent()))
    }
}
