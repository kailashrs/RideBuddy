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
    fun theOneDayPeriodCoversARollingTwentyFourHours() {
        val now = Instant.parse("2026-08-24T12:00:00Z").toEpochMilli()
        val rides = listOf(
            // 23 hours ago: inside the day, even though it is "yesterday" on the calendar.
            ride(start = now - 23 * 3_600_000L, distance = 40.0, durationHours = 1, speed = 40.0),
            // 25 hours ago: outside it, and so counts as the preceding window instead.
            ride(start = now - 25 * 3_600_000L, distance = 20.0, durationHours = 1, speed = 20.0),
        )

        val result = InsightsCalculator.calculate(rides, InsightPeriod.OneDay, now)

        assertEquals(1, result.rideCount)
        assertEquals(40.0, result.totalDistanceKilometres, 0.001)
        assertEquals(100.0, result.distanceChangePercent ?: 0.0, 0.001)
    }

    @Test
    fun theOneDayPeriodDoesNotEmptyItselfJustAfterMidnight() {
        // The calendar version made this tab useless around midnight: a ride from the
        // evening fell out of "today" the moment the date turned over, so a rider checking
        // their numbers at 00:30 saw nothing.
        val justAfterMidnight = Instant.parse("2026-08-25T00:30:00Z").toEpochMilli()
        val eveningRide = ride(
            start = Instant.parse("2026-08-24T19:00:00Z").toEpochMilli(),
            distance = 40.0,
            durationHours = 1,
            speed = 40.0,
        )

        val result = InsightsCalculator.calculate(listOf(eveningRide), InsightPeriod.OneDay, justAfterMidnight)

        assertEquals(1, result.rideCount)
        assertEquals(40.0, result.totalDistanceKilometres, 0.001)
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

        // The ride that did report fuel still counts; requiring every ride to report meant one
        // gap hid the whole period's figure.
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
    fun telemetryCoverageWeightsAveragesWithoutIncludingReconnectGaps() {
        val rides = listOf(
            ride(0, 36.0, 2, 36.0).copy(telemetryDurationMillis = 3_600_000L, averageRpm = 3_000.0),
            ride(1, 72.0, 1, 72.0).copy(telemetryDurationMillis = 3_600_000L, averageRpm = 6_000.0),
        )
        val result = InsightsCalculator.calculate(rides, InsightPeriod.AllTime, 10_000L)
        assertEquals(54.0, result.averageSpeedKph, 0.001)
        assertEquals(4_500.0, result.averageRpm, 0.001)
    }

    @Test
    fun aPeriodWithNoRidesCanStillHaveADecline() {
        val day = 86_400_000L
        val result = InsightsCalculator.calculate(listOf(ride(10 * day, 10.0, 1, 10.0)), InsightPeriod.SevenDays, 20 * day)
        assertEquals(-100.0, requireNotNull(result.distanceChangePercent), 0.0)
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
