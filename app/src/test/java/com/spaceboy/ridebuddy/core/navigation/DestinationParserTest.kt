package com.spaceboy.ridebuddy.core.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
