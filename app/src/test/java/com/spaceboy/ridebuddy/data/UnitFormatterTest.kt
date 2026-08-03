package com.spaceboy.ridebuddy.data

import java.util.Locale
import org.junit.Assert.assertEquals
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
}
