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
        assertEquals("47.0 mpg", UnitFormatter.mileage(20.0, DistanceUnits.Imperial, Locale.US))
    }

    @Test
    fun displaysMetricFuelEconomyAsMileage() {
        assertEquals("20.0 km/L", UnitFormatter.mileage(20.0, DistanceUnits.Metric, Locale.US))
    }

    @Test
    fun usesImperialGallonsForBritishMileage() {
        assertEquals("56.5 mpg", UnitFormatter.mileage(20.0, DistanceUnits.Imperial, Locale.UK))
        assertEquals("2.2 gal", UnitFormatter.fuel(10.0, DistanceUnits.Imperial, Locale.UK))
        assertEquals("2.6 gal", UnitFormatter.fuel(10.0, DistanceUnits.Imperial, Locale.US))
    }

    @Test
    fun unavailableFuelDataIsNotFormattedAsZero() {
        assertEquals("— km/L", UnitFormatter.mileage(null, DistanceUnits.Metric, Locale.US))
        assertEquals("— L", UnitFormatter.fuel(null, DistanceUnits.Metric, Locale.US))
    }

    @Test
    fun formatsManeuverDistanceInTheSelectedUnitSystem() {
        assertEquals("250 m", UnitFormatter.maneuverDistance(250, DistanceUnits.Metric, Locale.US))
        assertEquals("1.2 km", UnitFormatter.maneuverDistance(1_200, DistanceUnits.Metric, Locale.US))
        assertEquals("328 ft", UnitFormatter.maneuverDistance(100, DistanceUnits.Imperial, Locale.US))
        assertEquals("1.0 mi", UnitFormatter.maneuverDistance(1_609, DistanceUnits.Imperial, Locale.US))
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
