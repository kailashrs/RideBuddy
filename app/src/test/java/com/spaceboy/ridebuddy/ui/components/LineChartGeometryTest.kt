package com.spaceboy.ridebuddy.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LineChartGeometryTest {
    @Test
    fun singleSampleIsPlacedAtTheHorizontalCenter() {
        val points = lineChartPoints(
            values = listOf(7.0),
            width = 100f,
            height = 100f,
            scalePolicy = LineChartScalePolicy.AutoRange,
            clampNegativeValues = false,
        )

        assertEquals(1, points.size)
        assertEquals(50f, points.single().x, 0.001f)
        assertEquals(50f, points.single().y, 0.001f)
    }

    @Test
    fun zeroBasedScaleKeepsValuesRelativeToZero() {
        val points = lineChartPoints(
            values = listOf(40.0, 50.0),
            width = 100f,
            height = 100f,
            scalePolicy = LineChartScalePolicy.ZeroBased,
            clampNegativeValues = false,
        )

        assertEquals(20f, points[0].y, 0.001f)
        assertEquals(0f, points[1].y, 0.001f)
    }

    @Test
    fun zeroBasedScaleCanClampNegativeSamplesToTheBaseline() {
        val points = lineChartPoints(
            values = listOf(-10.0, 20.0),
            width = 100f,
            height = 100f,
            scalePolicy = LineChartScalePolicy.ZeroBased,
            clampNegativeValues = true,
        )

        assertEquals(100f, points[0].y, 0.001f)
        assertEquals(0f, points[1].y, 0.001f)
    }

    @Test
    fun zeroBasedScaleSkipsSeriesWithoutAPositiveMaximum() {
        val points = lineChartPoints(
            values = listOf(0.0, 0.0),
            width = 100f,
            height = 100f,
            scalePolicy = LineChartScalePolicy.ZeroBased,
            clampNegativeValues = false,
        )

        assertTrue(points.isEmpty())
    }

    @Test
    fun autoRangeDrawsAllZeroAndConstantSeriesOnTheBaseline() {
        val allZero = lineChartPoints(
            values = listOf(0.0, 0.0),
            width = 100f,
            height = 100f,
            scalePolicy = LineChartScalePolicy.AutoRange,
            clampNegativeValues = false,
        )
        val constant = lineChartPoints(
            values = listOf(7.0, 7.0),
            width = 100f,
            height = 100f,
            scalePolicy = LineChartScalePolicy.AutoRange,
            clampNegativeValues = false,
        )

        assertEquals(listOf(100f, 100f), allZero.map(LineChartPoint::y))
        assertEquals(listOf(100f, 100f), constant.map(LineChartPoint::y))
    }

    @Test
    fun autoRangeSupportsEntirelyNegativeSeries() {
        val points = lineChartPoints(
            values = listOf(-10.0, -5.0),
            width = 100f,
            height = 100f,
            scalePolicy = LineChartScalePolicy.AutoRange,
            clampNegativeValues = false,
        )

        assertEquals(100f, points[0].y, 0.001f)
        assertEquals(0f, points[1].y, 0.001f)
    }
}
