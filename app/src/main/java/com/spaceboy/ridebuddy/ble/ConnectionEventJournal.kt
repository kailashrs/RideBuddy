package com.spaceboy.ridebuddy.ble

import android.util.Log

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Ring size of the journal, in both memory and storage. Sized for the diagnostics screen. */
internal const val ConnectionEventLimit = 100

/**
 * Process-scoped connection journal. Recording is always an in-memory operation. When persistence
 * is enabled, history is loaded and committed by one conflated IO writer so Bluetooth callbacks
 * never wait for storage.
 */
internal class ConnectionEventJournal(
    private val store: ConnectionEventStore,
    scope: CoroutineScope,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val persistenceDebounceMillis: Long = DefaultPersistenceDebounceMillis,
    private val now: () -> LocalDateTime = LocalDateTime::now,
    initialPersistenceEnabled: Boolean = false,
    /**
     * Mirrors every event to logcat, so a release build's connection history reaches `adb logcat`
     * and every bugreport. Without it this journal is only readable through the app's own export,
     * which a non-debuggable build does not expose — the reason an entire BLE investigation had to
     * be reconstructed from HCI snoop instead of from what the app itself believed was happening.
     *
     * Injected so unit tests are not calling into the stubbed `android.util.Log`.
     */
    private val mirror: (String) -> Unit = { message -> Log.i(LogTag, message) },
) {
    private val mutableEvents = MutableStateFlow<List<String>>(emptyList())
    private val persistenceEnabled = MutableStateFlow(initialPersistenceEnabled)
    private val persistenceSignals = Channel<Unit>(Channel.CONFLATED)
    private val hasUnpersistedEvents = AtomicBoolean(false)

    val events: StateFlow<List<String>> = mutableEvents.asStateFlow()

    init {
        require(persistenceDebounceMillis >= 0L) { "Persistence debounce must not be negative" }
        // One long-lived writer owns all storage access, so recording never touches disk.
        // collectLatest means turning persistence off cancels an in-progress load rather
        // than letting it finish and repopulate the list the rider just asked to stop keeping.
        scope.launch(ioDispatcher) {
            var persistedEventsLoaded = false
            persistenceEnabled.collectLatest { enabled ->
                if (!enabled) return@collectLatest

                // Load once per process. Events recorded before persistence was switched
                // on are already in memory, so the stored history is merged into them
                // rather than replacing them.
                if (!persistedEventsLoaded) {
                    val persistedEvents = store.read().take(ConnectionEventLimit)
                    if (!persistenceEnabled.value) return@collectLatest
                    mutableEvents.update { currentEvents ->
                        mergeConnectionEvents(currentEvents, persistedEvents)
                    }
                    persistedEventsLoaded = true
                }

                // Anything recorded while persistence was off is still unwritten; kick the
                // writer so it is committed now rather than waiting for the next event.
                if (hasUnpersistedEvents.get()) persistenceSignals.trySend(Unit)
                for (ignored in persistenceSignals) {
                    awaitQuietPeriod()
                    if (!persistenceEnabled.value) continue
                    if (hasUnpersistedEvents.getAndSet(false)) {
                        if (!store.write(mutableEvents.value)) {
                            // Retry after the next event or after persistence is re-enabled.
                            hasUnpersistedEvents.set(true)
                        }
                    }
                }
            }
        }
    }

    /**
     * Adds one line to the journal. Safe to call from a Bluetooth callback: the list
     * update is in memory and the storage signal is a conflated, non-blocking send.
     */
    fun record(message: String) {
        mirror(message)
        val event = formatConnectionEvent(now(), message)
        mutableEvents.update { events -> prependConnectionEvent(events, event) }
        hasUnpersistedEvents.set(true)
        persistenceSignals.trySend(Unit)
    }

    fun setPersistenceEnabled(enabled: Boolean) {
        persistenceEnabled.value = enabled
    }

    /**
     * Waits until no new event has arrived for the debounce interval.
     *
     * Connection events come in bursts — a reconnect produces a dozen lines in under a
     * second — and committing after each one would mean a dozen synchronous writes. This
     * collapses a burst into a single commit at the end of it.
     */
    private suspend fun awaitQuietPeriod() {
        if (persistenceDebounceMillis == 0L) return
        while (
            withTimeoutOrNull(persistenceDebounceMillis) {
                persistenceSignals.receiveCatching().getOrNull()
            } != null
        ) {
            // Restart the quiet period whenever another event arrives.
        }
    }

    private companion object {
        const val DefaultPersistenceDebounceMillis = 250L

        /** One tag for the whole connection history, so `adb logcat -s RideBuddyBle` is enough. */
        const val LogTag = "RideBuddyBle"
    }
}

internal fun formatConnectionEvent(timestamp: LocalDateTime, message: String): String =
    "${timestamp.format(ConnectionEventTimestampFormatter)}  $message"

/** Newest first, oldest dropped past the limit — the order the diagnostics screen reads in. */
internal fun prependConnectionEvent(events: List<String>, event: String): List<String> =
    (listOf(event) + events).take(ConnectionEventLimit)

/**
 * Joins the in-memory list with the stored one. Both are already newest-first, so
 * concatenating keeps that order; `distinct` drops entries present in both, which happens
 * whenever the same session both recorded and persisted a line.
 */
internal fun mergeConnectionEvents(current: List<String>, persisted: List<String>): List<String> =
    (current + persisted).distinct().take(ConnectionEventLimit)

private val ConnectionEventTimestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
