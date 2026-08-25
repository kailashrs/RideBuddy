package com.spaceboy.ridebuddy.ble

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
) {
    private val mutableEvents = MutableStateFlow<List<String>>(emptyList())
    private val persistenceEnabled = MutableStateFlow(initialPersistenceEnabled)
    private val persistenceSignals = Channel<Unit>(Channel.CONFLATED)
    private val hasUnpersistedEvents = AtomicBoolean(false)

    val events: StateFlow<List<String>> = mutableEvents.asStateFlow()

    init {
        require(persistenceDebounceMillis >= 0L) { "Persistence debounce must not be negative" }
        scope.launch(ioDispatcher) {
            var persistedEventsLoaded = false
            persistenceEnabled.collectLatest { enabled ->
                if (!enabled) return@collectLatest

                if (!persistedEventsLoaded) {
                    val persistedEvents = store.read().take(ConnectionEventLimit)
                    if (!persistenceEnabled.value) return@collectLatest
                    mutableEvents.update { currentEvents ->
                        mergeConnectionEvents(currentEvents, persistedEvents)
                    }
                    persistedEventsLoaded = true
                }

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

    fun record(message: String) {
        val event = formatConnectionEvent(now(), message)
        mutableEvents.update { events -> prependConnectionEvent(events, event) }
        hasUnpersistedEvents.set(true)
        persistenceSignals.trySend(Unit)
    }

    fun setPersistenceEnabled(enabled: Boolean) {
        persistenceEnabled.value = enabled
    }

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
    }
}

internal fun formatConnectionEvent(timestamp: LocalDateTime, message: String): String =
    "${timestamp.format(ConnectionEventTimestampFormatter)}  $message"

internal fun prependConnectionEvent(events: List<String>, event: String): List<String> =
    (listOf(event) + events).take(ConnectionEventLimit)

internal fun mergeConnectionEvents(current: List<String>, persisted: List<String>): List<String> =
    (current + persisted).distinct().take(ConnectionEventLimit)

private val ConnectionEventTimestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
