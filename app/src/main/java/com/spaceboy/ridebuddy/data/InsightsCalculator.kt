package com.spaceboy.ridebuddy.data

import java.time.Clock
import java.time.LocalDate
import java.time.Period
import java.time.ZonedDateTime
import java.util.Locale

/**
 * Aggregates ride history for the insights screen.
 *
 * Pure and clock-injectable: every overload funnels into the one taking an explicit
 * zoned time, so period boundaries can be exercised directly.
 */
object InsightsCalculator {
    fun calculate(
        rides: List<Ride>,
        period: InsightPeriod,
        clock: Clock = Clock.systemDefaultZone(),
        locale: Locale = Locale.getDefault(),
    ): RideInsights = calculate(rides, period, ZonedDateTime.now(clock), locale)

    /**
     * Aggregates the rides started in [period] up to [now], with weeks starting where [locale]
     * starts them.
     *
     * [RideInsights.distanceChangePercent] compares like with like: the same stretch of the
     * period before, so today so far is set against yesterday up to this time, and this week so
     * far against last week up to the same moment rather than against all of it.
     */
    fun calculate(
        rides: List<Ride>,
        period: InsightPeriod,
        now: ZonedDateTime,
        locale: Locale,
    ): RideInsights {
        val nowMillis = now.toInstant().toEpochMilli()
        val window = period.window(now.toLocalDate(), locale)
        val currentStart = window?.let { (start, _) -> start.atStartOfDay(now.zone).toInstant().toEpochMilli() } ?: Long.MIN_VALUE
        val current = rides.filter { it.startedAtMillis in currentStart..nowMillis }

        val previousDistance = window?.let { (start, length) ->
            val previousStart = start.minus(length).atStartOfDay(now.zone).toInstant().toEpochMilli()
            val previousEnd = minOf(previousStart + (nowMillis - currentStart), currentStart)
            rides.filter { it.startedAtMillis in previousStart..<previousEnd }.sumOf(Ride::distanceKilometres)
        }
        // Null rather than 100% when the previous window holds no distance: there is no
        // meaningful percentage change from zero, and reporting one would be nonsense.
        val distanceChange = previousDistance?.takeIf { it > 0.0 }?.let { previous ->
            ((current.sumOf(Ride::distanceKilometres) - previous) / previous) * 100.0
        }
        if (current.isEmpty()) return RideInsights(distanceChangePercent = distanceChange)

        val totalDuration = current.sumOf(Ride::durationMillis)
        val fuelEstimates = current.mapNotNull(Ride::estimatedFuelLitres)
        // Duration-weighted, not a plain mean of the per-ride averages: a five-minute
        // commute would otherwise pull the average speed as hard as a three-hour tour.
        val weightedSeconds = current.sumOf { it.averagingDurationMillis / 1_000.0 }.takeIf { it > 0.0 }
        return RideInsights(
            rideCount = current.size,
            totalDistanceKilometres = current.sumOf(Ride::distanceKilometres),
            totalDurationMillis = totalDuration,
            estimatedFuelLitres = fuelEstimates.sum().takeIf { fuelEstimates.isNotEmpty() },
            averageRideDistanceKilometres = current.map(Ride::distanceKilometres).average(),
            averageRideDurationMillis = totalDuration / current.size,
            averageSpeedKph = weightedSeconds?.let { seconds -> current.sumOf { it.averageSpeedKph * it.averagingDurationMillis / 1_000.0 } / seconds }
                ?: 0.0,
            averageRpm = weightedSeconds?.let { seconds -> current.sumOf { it.averageRpm * it.averagingDurationMillis / 1_000.0 } / seconds }
                ?: 0.0,
            averageThrottlePercent = weightedSeconds?.let { seconds -> current.sumOf { it.averageThrottlePercent * it.averagingDurationMillis / 1_000.0 } / seconds }
                ?: 0.0,
            averageMileageKilometresPerLitre = current.combinedMileageKilometresPerLitre(),
            longestRideKilometres = current.maxOf(Ride::distanceKilometres),
            highestSpeedKph = current.maxOf(Ride::maximumSpeedKph),
            distanceChangePercent = distanceChange,
            bestZeroToSixtyMillis = current.mapNotNull(Ride::zeroToSixtyMillis).minOrNull(),
            bestZeroToHundredMillis = current.mapNotNull(Ride::zeroToHundredMillis).minOrNull(),
            peakAccelerationG = current.mapNotNull(Ride::peakAccelerationG).maxOrNull(),
            peakBrakingG = current.mapNotNull(Ride::peakBrakingG).maxOrNull(),
            distanceTrendKilometres = current
                .sortedBy(Ride::startedAtMillis)
                .takeLast(DistanceTrendRides)
                .map(Ride::distanceKilometres),
        )
    }

    /** Where [this] period starts on [today], and how long one such period is. Null for all time. */
    private fun InsightPeriod.window(today: LocalDate, locale: Locale): Pair<LocalDate, Period>? = when (this) {
        InsightPeriod.Today -> today to Period.ofDays(1)
        InsightPeriod.ThisWeek -> today.startOfWeek(locale) to Period.ofWeeks(1)
        InsightPeriod.ThisMonth -> today.withDayOfMonth(1) to Period.ofMonths(1)
        InsightPeriod.LastThreeMonths -> today.minusMonths(3) to Period.ofMonths(3)
        InsightPeriod.AllTime -> null
    }

    /** Rides in the trend sparkline. Enough to show a shape, few enough to stay legible. */
    private const val DistanceTrendRides = 14
}
