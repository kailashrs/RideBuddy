package com.spaceboy.ridebuddy.ble

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ConnectionEventStoreTest {
    @Test
    fun `event encoding preserves ordering unicode and line breaks`() {
        val events = listOf(
            "2026-08-25 10:00:00  BLE appeared",
            "event with unicode — motorcycle",
            "event with a quote \" slash \\ and\na line break",
        )

        val encoded = encodeConnectionEvents(events)

        assertEquals(
            "[\"2026-08-25 10:00:00  BLE appeared\",\"event with unicode — motorcycle\"," +
                "\"event with a quote \\\" slash \\\\ and\\na line break\"]",
            encoded,
        )
        assertEquals(events, decodeConnectionEvents(encoded))
    }

    @Test
    fun `corrupt JSON is rejected without throwing`() {
        assertEquals(emptyList<String>(), decodeConnectionEvents("not JSON"))
        assertEquals(emptyList<String>(), decodeConnectionEvents("[\"valid event\",]"))
        assertEquals(emptyList<String>(), decodeConnectionEvents("[\"unterminated]"))
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
