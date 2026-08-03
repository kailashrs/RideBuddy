package com.spaceboy.ridebuddy.data

import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InsightsCalculatorTest {
    @Test
    fun aggregatesCurrentPeriodAndComparesPreviousDistance() {
        val day = 86_400_000L
        val now = 100 * day
        val rides = listOf(
            ride(start = now - day, distance = 30.0, durationHours = 1, speed = 30.0),
            ride(start = now - 2 * day, distance = 20.0, durationHours = 1, speed = 20.0),
            ride(start = now - 8 * day, distance = 25.0, durationHours = 1, speed = 25.0),
        )

        val result = InsightsCalculator.calculate(rides, InsightPeriod.SevenDays, now)

        assertEquals(2, result.rideCount)
        assertEquals(50.0, result.totalDistanceKilometres, 0.001)
        assertEquals(25.0, result.averageSpeedKph, 0.001)
        assertEquals(100.0, result.distanceChangePercent ?: 0.0, 0.001)
    }

    @Test
    fun calculatesTodayPeriodCorrectly() {
        val now = System.currentTimeMillis()
        val todayStart = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val rides = listOf(
            ride(start = todayStart + 3_600_000L, distance = 40.0, durationHours = 1, speed = 40.0),
            ride(start = todayStart - 10_000L, distance = 20.0, durationHours = 1, speed = 20.0),
        )

        val result = InsightsCalculator.calculate(rides, InsightPeriod.Today, now)

        assertEquals(1, result.rideCount)
        assertEquals(40.0, result.totalDistanceKilometres, 0.001)
        assertEquals(100.0, result.distanceChangePercent ?: 0.0, 0.001)
    }

    @Test
    fun emptyInputProducesZeroesAndNoTrend() {
        val result = InsightsCalculator.calculate(emptyList(), InsightPeriod.ThirtyDays, 1_000L)

        assertEquals(0, result.rideCount)
        assertEquals(0.0, result.totalDistanceKilometres, 0.0)
        assertNull(result.distanceChangePercent)
    }

    private fun ride(start: Long, distance: Double, durationHours: Int, speed: Double) = Ride(
        id = start,
        startedAtMillis = start,
        endedAtMillis = start + durationHours * 3_600_000L,
        distanceKilometres = distance,
        averageSpeedKph = speed,
        maximumSpeedKph = speed + 10,
        averageRpm = 4_000.0,
        maximumRpm = 6_000,
        averageThrottlePercent = 25.0,
        averageConsumptionLPer100Km = 4.0,
        estimatedFuelLitres = distance * 0.04,
    )
}
