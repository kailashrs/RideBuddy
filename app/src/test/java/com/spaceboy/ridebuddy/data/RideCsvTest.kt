package com.spaceboy.ridebuddy.data

import java.io.StringWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RideCsvTest {
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


    @Test
    fun `a field is quoted only when the format requires it`() {
        assertEquals("plain", csvLine("plain"))
        assertEquals("\"has,comma\"", csvLine("has,comma"))
        assertEquals("\"has\"\"quote\"", csvLine("has\"quote"))
        assertEquals("\"has\nbreak\"", csvLine("has\nbreak"))
        assertEquals("1.5,42,", csvLine(1.5, 42, null))
    }

    @Test
    fun `the sample export escapes the same way the summary does`() {
        val written = StringWriter().apply {
            writeSampleCsv(listOf(sample(latitude = 12.97, longitude = 77.59)))
        }.toString()
        val row = written.trim().lines().last()

        assertEquals(10, row.split(",").size)
        assertTrue(row, row.startsWith("1970-01-01T00:00:01Z,"))
    }

    @Test
    fun `a sample with no location or mileage writes empty columns rather than null`() {
        val written = StringWriter().apply { writeSampleCsv(listOf(sample())) }.toString()
        val columns = written.trim().lines().last().split(",")

        assertEquals(10, columns.size)
        assertTrue(written, "null" !in written)
        // timestamp, speed, rpm, throttle populated; mileage empty; acceleration populated.
        assertEquals("", columns[4])
        assertEquals("", columns[6])
        assertEquals("", columns[9])
    }

    @Test
    fun `an empty sample series still carries the header`() {
        val written = StringWriter().apply { writeSampleCsv(emptyList()) }.toString()

        assertEquals(1, written.trim().lines().size)
    }

    private fun sample(latitude: Double? = null, longitude: Double? = null) = RideSample(
        timestampMillis = 1_000,
        speedKph = 36.0,
        rpm = 4_000,
        throttlePercent = 20,
        mileageKilometresPerLitre = null,
        accelerationMetresPerSecondSquared = 0.5,
        latitude = latitude,
        longitude = longitude,
    )

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
