package com.spaceboy.ridebuddy.ble

import java.time.LocalDateTime
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionEventJournalTest {
    @Test
    fun `journal loads previous events and persists new snapshots off its writer`() {
        val previous = listOf("2026-08-25 10:00:00  Previous event")
        val store = RecordingConnectionEventStore(previous)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

        try {
            val journal = ConnectionEventJournal(
                store = store,
                scope = scope,
                ioDispatcher = Dispatchers.Unconfined,
                persistenceDebounceMillis = 0L,
                now = { LocalDateTime.of(2026, 8, 25, 12, 34, 56) },
                initialPersistenceEnabled = true,
            )

            assertEquals(previous, journal.events.value)

            journal.record("GATT disconnected")

            val expected = listOf(
                "2026-08-25 12:34:56  GATT disconnected",
                "2026-08-25 10:00:00  Previous event",
            )
            assertEquals(expected, journal.events.value)
            assertEquals(listOf(expected), store.writes)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `load merge keeps current process events ahead of persisted history`() {
        val current = listOf("current", "duplicate")
        val persisted = listOf("duplicate", "persisted")

        assertEquals(
            listOf("current", "duplicate", "persisted"),
            mergeConnectionEvents(current, persisted),
        )
    }

    @Test
    fun `burst of events is persisted as one latest snapshot`() = runBlocking {
        val store = RecordingConnectionEventStore(emptyList())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        try {
            val journal = ConnectionEventJournal(
                store = store,
                scope = scope,
                persistenceDebounceMillis = 30L,
                now = { LocalDateTime.of(2026, 8, 25, 12, 34, 56) },
                initialPersistenceEnabled = true,
            )

            journal.record("first")
            journal.record("second")
            journal.record("third")

            withTimeout(2_000L) {
                while (store.writes.isEmpty()) delay(10L)
            }
            delay(75L)

            assertEquals(1, store.writes.size)
            assertEquals(
                listOf(
                    "2026-08-25 12:34:56  third",
                    "2026-08-25 12:34:56  second",
                    "2026-08-25 12:34:56  first",
                ),
                store.writes.single(),
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `disabled persistence keeps diagnostics in memory without touching storage`() {
        val store = RecordingConnectionEventStore(listOf("persisted"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

        try {
            val journal = ConnectionEventJournal(
                store = store,
                scope = scope,
                ioDispatcher = Dispatchers.Unconfined,
                persistenceDebounceMillis = 0L,
                now = { LocalDateTime.of(2026, 8, 25, 12, 34, 56) },
            )

            journal.record("Live event")

            assertEquals(listOf("2026-08-25 12:34:56  Live event"), journal.events.value)
            assertEquals(0, store.readCount.get())
            assertTrue(store.writes.isEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `enabling persistence loads history and writes events recorded while disabled`() {
        val store = RecordingConnectionEventStore(listOf("persisted"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

        try {
            val journal = ConnectionEventJournal(
                store = store,
                scope = scope,
                ioDispatcher = Dispatchers.Unconfined,
                persistenceDebounceMillis = 0L,
                now = { LocalDateTime.of(2026, 8, 25, 12, 34, 56) },
            )
            journal.record("Live event")

            journal.setPersistenceEnabled(true)

            val expected = listOf("2026-08-25 12:34:56  Live event", "persisted")
            assertEquals(expected, journal.events.value)
            assertEquals(1, store.readCount.get())
            assertEquals(listOf(expected), store.writes)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `disabling persistence stops later writes`() {
        val store = RecordingConnectionEventStore(emptyList())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

        try {
            val journal = ConnectionEventJournal(
                store = store,
                scope = scope,
                ioDispatcher = Dispatchers.Unconfined,
                persistenceDebounceMillis = 0L,
                now = { LocalDateTime.of(2026, 8, 25, 12, 34, 56) },
                initialPersistenceEnabled = true,
            )
            journal.record("Persisted event")
            journal.setPersistenceEnabled(false)

            journal.record("Session-only event")

            assertEquals(1, store.writes.size)
            assertEquals(
                listOf("2026-08-25 12:34:56  Persisted event"),
                store.writes.single(),
            )
            assertEquals(
                listOf(
                    "2026-08-25 12:34:56  Session-only event",
                    "2026-08-25 12:34:56  Persisted event",
                ),
                journal.events.value,
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `journal constructor rejects a negative persistence debounce`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

        try {
            val failure = runCatching {
                ConnectionEventJournal(
                    store = RecordingConnectionEventStore(emptyList()),
                    scope = scope,
                    ioDispatcher = Dispatchers.Unconfined,
                    persistenceDebounceMillis = -1L,
                )
            }

            assertTrue(failure.exceptionOrNull() is IllegalArgumentException)
        } finally {
            scope.cancel()
        }
    }
}

private class RecordingConnectionEventStore(
    private val initialEvents: List<String>,
) : ConnectionEventStore {
    val readCount = AtomicInteger(0)
    val writes = CopyOnWriteArrayList<List<String>>()

    override fun read(): List<String> {
        readCount.incrementAndGet()
        return initialEvents
    }

    override fun write(events: List<String>): Boolean {
        writes += events.toList()
        return true
    }
}
