package com.spaceboy.ridebuddy.ble

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionEventStoreTest {
    @Test
    fun `event encoding round-trips ordering, unicode, separators and control characters`() {
        val events = listOf(
            "2026-08-25 10:00:00  BLE appeared",
            "event with unicode — motorcycle 🏍",
            "event with a quote \" slash \\ and\na line break",
            "event with a percent 100% and a tab\tinside",
            "event with a control character \u0001 in it",
        )

        val encoded = encodeConnectionEvents(events)

        // The encoded form is pure ASCII, so nothing in a log line can reach the preferences XML
        // as a raw control character or an unpaired surrogate.
        assertTrue(encoded.all { it.code in 0x20..0x7E || it == '\n' })
        assertEquals(events, decodeConnectionEvents(encoded))
    }

    @Test
    fun `an empty journal round-trips`() {
        assertEquals(emptyList<String>(), decodeConnectionEvents(encodeConnectionEvents(emptyList())))
    }

    @Test
    fun `unreadable stored data is treated as an empty journal`() {
        assertEquals(emptyList<String>(), decodeConnectionEvents("%"))
        assertEquals(emptyList<String>(), decodeConnectionEvents("%zz"))
    }

    @Test
    fun `journal remains bounded and newest first`() {
        val existing = (0 until ConnectionEventLimit).map { "event $it" }
        val updated = prependConnectionEvent(existing, "new event")

        assertEquals(ConnectionEventLimit, updated.size)
        assertEquals("new event", updated.first())
        assertFalse("event ${ConnectionEventLimit - 1}" in updated)
    }

    @Test
    fun `durable timestamps include the date`() {
        assertEquals(
            "2026-08-25 12:34:56  GATT disconnected",
            formatConnectionEvent(LocalDateTime.of(2026, 8, 25, 12, 34, 56), "GATT disconnected"),
        )
    }
}
