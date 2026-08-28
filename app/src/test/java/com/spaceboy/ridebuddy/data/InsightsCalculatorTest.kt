package com.spaceboy.ridebuddy.data

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
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

    @Test
    fun mileageIsDerivedFromCombinedDistanceAndFuel() {
        val now = 10_000L
        val rides = listOf(
            ride(start = 8_000L, distance = 1.0, durationHours = 1, speed = 10.0, fuelLitres = 0.1),
            ride(start = 9_000L, distance = 100.0, durationHours = 1, speed = 20.0, fuelLitres = 5.0),
        )

        val result = InsightsCalculator.calculate(rides, InsightPeriod.AllTime, now)

        assertEquals(5.1, requireNotNull(result.estimatedFuelLitres), 0.000_001)
        assertEquals(101.0 / 5.1, requireNotNull(result.averageMileageKilometresPerLitre), 0.000_001)
    }

    @Test
    fun ridesWithoutFuelDataDoNotInflateMileage() {
        val rides = listOf(
            ride(start = 8_000L, distance = 10.0, durationHours = 1, speed = 10.0, fuelLitres = 0.5),
            ride(start = 9_000L, distance = 100.0, durationHours = 1, speed = 20.0, fuelLitres = null),
        )

        val result = InsightsCalculator.calculate(rides, InsightPeriod.AllTime, 10_000L)

        assertEquals(0.5, requireNotNull(result.estimatedFuelLitres), 0.0)
        assertEquals(20.0, requireNotNull(result.averageMileageKilometresPerLitre), 0.0)
    }

    @Test
    fun `the distance trend follows the period, oldest ride first`() {
        val day = 86_400_000L
        val now = 100 * day
        val rides = listOf(
            ride(start = now - day, distance = 30.0, durationHours = 1, speed = 30.0),
            ride(start = now - 2 * day, distance = 20.0, durationHours = 1, speed = 20.0),
            // Outside the seven-day window, so it must not reach the chart.
            ride(start = now - 8 * day, distance = 25.0, durationHours = 1, speed = 25.0),
        )

        val trend = InsightsCalculator
            .calculate(rides, InsightPeriod.SevenDays, clockAt(now))
            .distanceTrendKilometres

        assertEquals(listOf(20.0, 30.0), trend)
    }

    @Test
    fun `the week summary starts at the locale's first day of the week`() {
        // 2026-08-27 is a Thursday. With a Monday-first locale the week starts on the 24th,
        // so the Sunday ride before it is excluded and the Tuesday ride is not.
        val now = Instant.parse("2026-08-27T12:00:00Z").toEpochMilli()
        val rides = listOf(
            ride(start = Instant.parse("2026-08-25T09:00:00Z").toEpochMilli(), distance = 30.0, durationHours = 1, speed = 30.0),
            ride(start = Instant.parse("2026-08-26T09:00:00Z").toEpochMilli(), distance = 10.0, durationHours = 3, speed = 10.0),
            ride(start = Instant.parse("2026-08-23T09:00:00Z").toEpochMilli(), distance = 99.0, durationHours = 1, speed = 99.0),
        )

        val summary = InsightsCalculator.weekSummary(rides, now, fixedZone, Locale.UK)

        assertEquals(2, summary.rideCount)
        assertEquals(40.0, summary.distanceKilometres, 0.001)
        assertEquals(2 * 3_600_000L, summary.averageDurationMillis)
    }

    @Test
    fun `an empty week reports zeroes rather than a partial summary`() {
        val now = Instant.parse("2026-08-27T12:00:00Z").toEpochMilli()

        val summary = InsightsCalculator.weekSummary(emptyList(), now, fixedZone, Locale.UK)

        assertEquals(0, summary.rideCount)
        assertEquals(0.0, summary.distanceKilometres, 0.0)
        assertNull(summary.mileageKilometresPerLitre)
    }

    private fun ride(
        start: Long,
        distance: Double,
        durationHours: Int,
        speed: Double,
        fuelLitres: Double? = distance / 25.0,
    ) = Ride(
        id = start,
        startedAtMillis = start,
        endedAtMillis = start + durationHours * 3_600_000L,
        distanceKilometres = distance,
        averageSpeedKph = speed,
        maximumSpeedKph = speed + 10,
        averageRpm = 4_000.0,
        maximumRpm = 6_000,
        averageThrottlePercent = 25.0,
        estimatedFuelLitres = fuelLitres,
    )
}
