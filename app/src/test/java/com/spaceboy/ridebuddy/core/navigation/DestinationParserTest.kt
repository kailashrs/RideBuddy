package com.spaceboy.ridebuddy.core.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class DestinationParserTest {
    @Test
    fun parsesPercentEncodedCoordinatesWithoutGeocoding() {
        val destination = directNavigationDestination(
            "https://www.google.com/maps/dir/?api=1&destination=12.9716%2C77.5946",
        )

        assertEquals(12.9716, destination?.latitude ?: 0.0, 0.0)
        assertEquals(77.5946, destination?.longitude ?: 0.0, 0.0)
    }

    @Test
    fun rejectsOutOfRangeEncodedCoordinates() {
        assertNull(
            directNavigationDestination(
                "https://www.google.com/maps/search/?api=1&query=91.0%2C181.0",
            ),
        )
    }

    @Test
    fun redirectTimeoutUsesOnlyTheRemainingDeadline() {
        assertEquals(
            2_500,
            remainingExpansionTimeoutMillis(
                deadlineNanos = 3_500_000_000L,
                nowNanos = 1_000_000_000L,
            ),
        )
        assertEquals(
            8_000,
            remainingExpansionTimeoutMillis(
                deadlineNanos = 20_000_000_000L,
                nowNanos = 1_000_000_000L,
            ),
        )
    }

    @Test
    fun expiredRedirectDeadlineFailsWithAUserFacingError() {
        val error = assertThrows(DestinationExpansionTimeoutException::class.java) {
            remainingExpansionTimeoutMillis(deadlineNanos = 1L, nowNanos = 2L)
        }

        assertEquals("Timed out while opening that shared Maps link", error.message)
    }
}
