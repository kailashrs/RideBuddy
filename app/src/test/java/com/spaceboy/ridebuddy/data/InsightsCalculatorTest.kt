package com.spaceboy.ridebuddy.data

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InsightsCalculatorTest {
    private val zone = ZoneId.of("Asia/Kolkata")

    /** Thursday 20 August 2026, 18:00 in the rider's zone. */
    private val now = ZonedDateTime.of(2026, 8, 20, 18, 0, 0, 0, zone)

    private fun at(text: String): Long = LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()

    private fun calculate(rides: List<Ride>, period: InsightPeriod, locale: Locale = Locale.UK) =
        InsightsCalculator.calculate(rides, period, now, locale)

    @Test
    fun todayStartsAtLocalMidnightAndComparesWithYesterdayUpToTheSameTime() {
        val rides = listOf(
            ride(start = at("2026-08-20T07:00"), distance = 30.0, durationHours = 1, speed = 30.0),
            // Late last night: yesterday on the calendar, so not today.
            ride(start = at("2026-08-19T23:30"), distance = 50.0, durationHours = 1, speed = 50.0),
            // Yesterday before 18:00 is the comparison; yesterday's late ride is not.
            ride(start = at("2026-08-19T09:00"), distance = 20.0, durationHours = 1, speed = 20.0),
        )

        val result = calculate(rides, InsightPeriod.Today)

        assertEquals(1, result.rideCount)
        assertEquals(30.0, result.totalDistanceKilometres, 0.001)
        assertEquals(50.0, requireNotNull(result.distanceChangePercent), 0.001)
    }

    @Test
    fun thisWeekStartsOnTheLocalesFirstDayOfTheWeek() {
        // Sunday 16 August: this week in the US, last week in the UK, whose weeks start on Monday.
        val sunday = listOf(ride(start = at("2026-08-16T10:00"), distance = 40.0, durationHours = 1, speed = 40.0))

        assertEquals(1, calculate(sunday, InsightPeriod.ThisWeek, Locale.US).rideCount)
        assertEquals(0, calculate(sunday, InsightPeriod.ThisWeek, Locale.UK).rideCount)
    }

    @Test
    fun thisMonthIsTheCalendarMonthComparedWithLastMonthToTheSameDay() {
        val rides = listOf(
            ride(start = at("2026-08-01T08:00"), distance = 30.0, durationHours = 1, speed = 30.0),
            ride(start = at("2026-08-20T08:00"), distance = 30.0, durationHours = 1, speed = 30.0),
            ride(start = at("2026-07-31T08:00"), distance = 90.0, durationHours = 1, speed = 30.0),
            ride(start = at("2026-07-10T08:00"), distance = 40.0, durationHours = 1, speed = 30.0),
        )

        val result = calculate(rides, InsightPeriod.ThisMonth)

        assertEquals(2, result.rideCount)
        assertEquals(60.0, result.totalDistanceKilometres, 0.001)
        // Only 1–20 July compares with 1–20 August, so the end-of-July ride is left out.
        assertEquals(50.0, requireNotNull(result.distanceChangePercent), 0.001)
    }

    @Test
    fun lastThreeMonthsReachesBackToThisDayThreeMonthsAgo() {
        val rides = listOf(
            ride(start = at("2026-05-20T08:00"), distance = 25.0, durationHours = 1, speed = 25.0),
            ride(start = at("2026-05-19T08:00"), distance = 25.0, durationHours = 1, speed = 25.0),
        )

        val result = calculate(rides, InsightPeriod.LastThreeMonths)

        assertEquals(1, result.rideCount)
        assertEquals(0.0, requireNotNull(result.distanceChangePercent), 0.001)
    }

    @Test
    fun theDistanceTrendFollowsThePeriodOldestRideFirst() {
        val rides = listOf(
            ride(start = at("2026-08-19T08:00"), distance = 30.0, durationHours = 1, speed = 30.0),
            ride(start = at("2026-08-18T08:00"), distance = 20.0, durationHours = 1, speed = 20.0),
            // Last week, so it must not reach the chart.
            ride(start = at("2026-08-14T08:00"), distance = 25.0, durationHours = 1, speed = 25.0),
        )

        assertEquals(listOf(20.0, 30.0), calculate(rides, InsightPeriod.ThisWeek).distanceTrendKilometres)
    }

    @Test
    fun aPeriodWithNoRidesCanStillHaveADecline() {
        val lastWeek = listOf(ride(start = at("2026-08-12T08:00"), distance = 10.0, durationHours = 1, speed = 10.0))
        assertEquals(-100.0, requireNotNull(calculate(lastWeek, InsightPeriod.ThisWeek).distanceChangePercent), 0.0)
    }

    @Test
    fun emptyInputProducesZeroesAndNoTrend() {
        val result = calculate(emptyList(), InsightPeriod.ThisMonth)

        assertEquals(0, result.rideCount)
        assertEquals(0.0, result.totalDistanceKilometres, 0.0)
        assertNull(result.distanceChangePercent)
    }

    @Test
    fun mileageIsDerivedFromCombinedDistanceAndFuel() {
        val rides = listOf(
            ride(start = 8_000L, distance = 1.0, durationHours = 1, speed = 10.0, fuelLitres = 0.1),
            ride(start = 9_000L, distance = 100.0, durationHours = 1, speed = 20.0, fuelLitres = 5.0),
        )

        val result = calculate(rides, InsightPeriod.AllTime)

        assertEquals(5.1, requireNotNull(result.estimatedFuelLitres), 0.000_001)
        assertEquals(101.0 / 5.1, requireNotNull(result.averageMileageKilometresPerLitre), 0.000_001)
    }

    @Test
    fun ridesWithoutFuelDataDoNotInflateMileage() {
        val rides = listOf(
            ride(start = 8_000L, distance = 10.0, durationHours = 1, speed = 10.0, fuelLitres = 0.5),
            ride(start = 9_000L, distance = 100.0, durationHours = 1, speed = 20.0, fuelLitres = null),
        )

        val result = calculate(rides, InsightPeriod.AllTime)

        // The ride that did report fuel still counts; requiring every ride to report meant one
        // gap hid the whole period's figure.
        assertEquals(0.5, requireNotNull(result.estimatedFuelLitres), 0.0)
        assertEquals(20.0, requireNotNull(result.averageMileageKilometresPerLitre), 0.0)
    }

    @Test
    fun telemetryCoverageWeightsAveragesWithoutIncludingReconnectGaps() {
        val rides = listOf(
            ride(0, 36.0, 2, 36.0).copy(telemetryDurationMillis = 3_600_000L, averageRpm = 3_000.0),
            ride(1, 72.0, 1, 72.0).copy(telemetryDurationMillis = 3_600_000L, averageRpm = 6_000.0),
        )
        val result = calculate(rides, InsightPeriod.AllTime)
        assertEquals(54.0, result.averageSpeedKph, 0.001)
        assertEquals(4_500.0, result.averageRpm, 0.001)
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
