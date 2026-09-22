package com.spaceboy.ridebuddy.core.alerts

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The request URL itself is asserted in `ForecastRequestAndroidTest`, because `Uri.Builder`
 * is a stub off-device. What is checked here is the part that was wrong: how a coordinate
 * becomes text.
 */
class ForecastRequestTest {
    @Test
    fun `a coordinate near zero is written as a number rather than in scientific notation`() {
        // Double.toString gives "1.0E-5" and "-4.0E-4" here, which is what used to be sent
        // for a position within about a hundred metres of the equator or the prime meridian.
        assertEquals("0.000010", 0.00001.asCoordinate())
        assertEquals("-0.000400", (-0.0004).asCoordinate())
        assertEquals("0.000000", 0.0.asCoordinate())
    }

    @Test
    fun `an ordinary coordinate keeps the precision a forecast can use`() {
        assertEquals("12.970000", 12.97.asCoordinate())
        assertEquals("77.594563", 77.5945627.asCoordinate())
        assertEquals("-33.867487", (-33.8674869).asCoordinate())
    }

    @Test
    fun `the decimal separator does not follow the phone's locale`() {
        val previous = java.util.Locale.getDefault()
        try {
            // A German locale would otherwise render this as "12,970000" and split the query.
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            assertEquals("12.970000", 12.97.asCoordinate())
        } finally {
            java.util.Locale.setDefault(previous)
        }
    }
}
