package com.spaceboy.ridebuddy.data

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InsightsCalculatorTest {
    private val fixedZone: ZoneOffset = ZoneOffset.UTC

    private fun clockAt(millis: Long): Clock = Clock.fixed(Instant.ofEpochMilli(millis), fixedZone)

    @Test
    fun aggregatesCurrentPeriodAndComparesPreviousDistance() {
        val day = 86_400_000L
        val now = 100 * day
        val rides = listOf(
            ride(start = now - day, distance = 30.0, durationHours = 1, speed = 30.0),
            ride(start = now - 2 * day, distance = 20.0, durationHours = 1, speed = 20.0),
            ride(start = now - 8 * day, distance = 25.0, durationHours = 1, speed = 25.0),
        )

        val result = InsightsCalculator.calculate(rides, InsightPeriod.SevenDays, clockAt(now))

        assertEquals(2, result.rideCount)
        assertEquals(50.0, result.totalDistanceKilometres, 0.001)
        assertEquals(25.0, result.averageSpeedKph, 0.001)
        assertEquals(100.0, result.distanceChangePercent ?: 0.0, 0.001)
    }

    @Test
    fun calculatesTodayPeriodCorrectly() {
        // Pin to 12:00 UTC so "today" is unambiguous regardless of host timezone.
        val now = Instant.parse("2026-08-24T12:00:00Z").toEpochMilli()
        val todayStart = Instant.parse("2026-08-24T00:00:00Z").toEpochMilli()

        val rides = listOf(
            ride(start = todayStart + 3_600_000L, distance = 40.0, durationHours = 1, speed = 40.0),
            ride(start = todayStart - 10_000L, distance = 20.0, durationHours = 1, speed = 20.0),
        )

        val result = InsightsCalculator.calculate(rides, InsightPeriod.Today, clockAt(now))

        assertEquals(1, result.rideCount)
        assertEquals(40.0, result.totalDistanceKilometres, 0.001)
        assertEquals(100.0, result.distanceChangePercent ?: 0.0, 0.001)
    }

    @Test
    fun calculatesTodayPeriodHonoursExplicitZone() {
        // 23:30 UTC on 24 Aug is the very start of 25 Aug in +05:30.
        val now = Instant.parse("2026-08-24T23:30:00Z").toEpochMilli()

        // Both rides happen to fall on 24 Aug in UTC (the rides themselves are
        // stored as UTC instants) but on 25 Aug in +05:30.
        val rides = listOf(
            ride(start = Instant.parse("2026-08-24T19:00:00Z").toEpochMilli(), distance = 40.0, durationHours = 1, speed = 40.0),
            ride(start = Instant.parse("2026-08-24T15:00:00Z").toEpochMilli(), distance = 20.0, durationHours = 1, speed = 20.0),
        )

        val utcClock = clockAt(now)
        val istClock = Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.ofHoursMinutes(5, 30))

        assertEquals(
            "From a UTC clock today is 24 Aug and both rides are in scope",
            2,
            InsightsCalculator.calculate(rides, InsightPeriod.Today, utcClock).rideCount,
        )
        assertEquals(
            "From an +05:30 clock today is 25 Aug; only the 00:30 IST ride is in scope",
            1,
            InsightsCalculator.calculate(rides, InsightPeriod.Today, istClock).rideCount,
        )
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