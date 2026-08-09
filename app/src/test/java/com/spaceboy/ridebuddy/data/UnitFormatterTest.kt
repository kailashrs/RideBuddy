package com.spaceboy.ridebuddy.data

import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class UnitFormatterTest {
    @Test
    fun convertsMetricTelemetryToImperial() {
        assertEquals("62 mph", UnitFormatter.speed(100.0, DistanceUnits.Imperial, Locale.US))
        assertEquals("62.1 mi", UnitFormatter.distance(100.0, DistanceUnits.Imperial, Locale.US))
        assertEquals("47.0 mpg", UnitFormatter.consumption(5.0, DistanceUnits.Imperial, Locale.US))
    }

    @Test
    fun displaysMetricFuelEconomyAsMileage() {
        assertEquals("20.0 km/L", UnitFormatter.consumption(5.0, DistanceUnits.Metric, Locale.US))
    }

    @Test
    fun usesImperialGallonsForBritishMileage() {
        assertEquals("56.5 mpg", UnitFormatter.consumption(5.0, DistanceUnits.Imperial, Locale.UK))
    }

    @Test
    fun usesRegionalDistanceAndSpeedDefaultsUntilTheUserChoosesOtherwise() {
        assertEquals(DistanceUnits.Imperial, DistanceUnits.defaultFor(Locale.UK))
        val india = Locale.Builder().setLanguage("en").setRegion("IN").build()
        assertEquals(DistanceUnits.Metric, DistanceUnits.defaultFor(india))
    }

    @Test
    fun dateTimeFormattingPreservesEachDisplayStyle() = withUsUtcDefaults {
        val timestamp = 1_704_163_445_000L

        assertEquals(
            DateFormat.getDateTimeInstance().format(Date(timestamp)),
            UnitFormatter.formatDateTime(timestamp),
        )
        assertEquals(
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(timestamp)),
            UnitFormatter.formatShortDateTime(timestamp),
        )
        assertNotEquals(UnitFormatter.formatDateTime(timestamp), UnitFormatter.formatShortDateTime(timestamp))
    }

    @Test
    fun timeFormattingRemainsShort() = withUsUtcDefaults {
        val timestamp = 1_704_163_445_000L

        assertEquals(
            DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(timestamp)),
            UnitFormatter.formatTime(timestamp),
        )
    }

    private fun withUsUtcDefaults(block: () -> Unit) {
        val originalLocale = Locale.getDefault()
        val originalTimeZone = TimeZone.getDefault()
        try {
            Locale.setDefault(Locale.US)
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            block()
        } finally {
            Locale.setDefault(originalLocale)
            TimeZone.setDefault(originalTimeZone)
        }
    }
}
