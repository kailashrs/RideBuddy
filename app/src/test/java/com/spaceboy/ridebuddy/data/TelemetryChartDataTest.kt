package com.spaceboy.ridebuddy.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryChartDataTest {
    @Test fun downsamplingPreservesBriefPeaksAndEndpoints() {
        val samples = List(100) { sample(it * 250L, if (it == 37) 120.0 else 20.0) }
        val chart = telemetryChartData(samples, 10) { it.speedKph }
        assertTrue(120.0 in chart.values)
        assertEquals(0L, chart.timestampsMillis.first())
        assertEquals(24_750L, chart.timestampsMillis.last())
        assertTrue(null !in chart.values)
    }

    @Test fun sourceGapsBreakTheLineEvenAfterThinning() {
        val samples = List(20) { sample(it * 250L + if (it >= 10) 10_000L else 0L, it.toDouble()) }
        val chart = telemetryChartData(samples, 4) { it.speedKph }
        assertTrue(null in chart.values)
        assertEquals(chart.values.size, chart.timestampsMillis.size)
    }

    private fun sample(time: Long, speed: Double) = RideSample(time, speed, 1_000L, 10, 20.0, 0.0)
}
