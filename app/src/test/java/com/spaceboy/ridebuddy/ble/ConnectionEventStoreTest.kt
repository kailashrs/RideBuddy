package com.spaceboy.ridebuddy.ble

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ConnectionEventStoreTest {
    private val file = Files.createTempDirectory("journal").resolve("connection_journal.txt").toFile()

    @Test
    fun `events round-trip in order, with line breaks flattened`() {
        val store = FileConnectionEventStore(file)
        val events = listOf("2026-08-25 10:00:00  BLE appeared", "unicode — motorcycle 🏍", "a line\nbreak")

        assertTrue(store.write(events))

        assertEquals(listOf(events[0], events[1], "a line break"), FileConnectionEventStore(file).read())
    }

    @Test
    fun `writes are capped at the journal limit`() {
        val store = FileConnectionEventStore(file)

        store.write((1..ConnectionEventLimit + 20).map { "event $it" })

        assertEquals(ConnectionEventLimit, store.read().size)
    }
}
