package com.spaceboy.ridebuddy.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RideHistoryCsvTest {
    @Test
    fun `area names containing commas and quotes stay in one column`() {
        val csv = listOf(ride(startArea = "Bengaluru, KA", endArea = "The \"Old\" Road")).toCsv()
        val row = csv.trim().lines().last()

        assertTrue(row, "\"Bengaluru, KA\"" in row)
        assertTrue(row, "\"The \"\"Old\"\" Road\"" in row)
    }

    @Test
    fun `absent optional values are written as empty columns rather than null`() {
        val csv = listOf(ride()).toCsv()
        val columns = csv.trim().lines().last().split(",")

        assertEquals(14, columns.size)
        assertTrue(csv, "null" !in csv)
    }

    @Test
    fun `an empty history still carries the header`() {
        assertEquals(1, emptyList<Ride>().toCsv().trim().lines().size)
    }

    private fun ride(startArea: String? = null, endArea: String? = null) = Ride(
        id = 1,
        startedAtMillis = 1_000,
        endedAtMillis = 2_000,
        distanceKilometres = 1.0,
        averageSpeedKph = 10.0,
        maximumSpeedKph = 20.0,
        averageRpm = 3_000.0,
        maximumRpm = 6_000,
        averageThrottlePercent = 15.0,
        estimatedFuelLitres = null,
        startArea = startArea,
        endArea = endArea,
    )
}
