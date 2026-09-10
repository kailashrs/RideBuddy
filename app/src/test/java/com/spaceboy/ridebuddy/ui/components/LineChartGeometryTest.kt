package com.spaceboy.ridebuddy.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LineChartGeometryTest {
    @Test fun zeroBasedScaleKeepsValuesRelativeToZero() {
        val points = lineChartSegments(listOf(40.0, 50.0), 100f, 100f).flatten()
        assertEquals(20f, points[0].y, 0.001f)
        assertEquals(0f, points[1].y, 0.001f)
    }

    @Test fun negativeSamplesAreClampedToTheBaseline() {
        val points = lineChartSegments(listOf(-10.0, 20.0), 100f, 100f).flatten()
        assertEquals(100f, points[0].y, 0.001f)
        assertEquals(0f, points[1].y, 0.001f)
    }

    @Test fun stationaryTelemetryRemainsVisibleAtZero() {
        val points = lineChartSegments(listOf(0.0, 0.0), 100f, 100f).flatten()
        assertEquals(listOf(100f, 100f), points.map(LineChartPoint::y))
    }

    @Test fun unavailableValuesBreakTheLineWithoutCompressingTime() {
        val segments = lineChartSegments(listOf(10.0, null, 20.0), 100f, 100f)
        assertEquals(2, segments.size)
        assertEquals(0f, segments[0].single().x, 0.001f)
        assertEquals(100f, segments[1].single().x, 0.001f)
    }

    @Test fun noReadingsProduceNoGeometry() {
        assertTrue(lineChartSegments(listOf(null, Double.NaN, Double.POSITIVE_INFINITY), 100f, 100f).isEmpty())
    }

    @Test fun timestampsKeepIrregularTelemetryAtItsActualTimePosition() {
        val points = lineChartSegments(listOf(10.0, 20.0, 30.0), 100f, 100f,
            listOf(1_000L, 1_100L, 2_000L)).flatten()
        assertEquals(listOf(0f, 10f, 100f), points.map(LineChartPoint::x))
    }

    @Test fun singlePointIsCenteredHorizontallyAndRetainsItsMagnitude() {
        val points = lineChartSegments(listOf(7.0), 100f, 100f).flatten()
        assertEquals(50f, points.single().x, 0f)
        assertEquals(0f, points.single().y, 0f)
    }
}
