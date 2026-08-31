package com.spaceboy.ridebuddy.data

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * Aggregates ride history for the insights screen.
 *
 * Pure and clock-injectable: every overload funnels into the one taking an explicit
 * timestamp and zone, so period boundaries can be exercised directly.
 */
object InsightsCalculator {
    fun calculate(
        rides: List<Ride>,
        period: InsightPeriod,
        clock: Clock = Clock.systemDefaultZone(),
    ): RideInsights = calculate(rides, period, clock.millis(), clock.zone)

    fun calculate(
        rides: List<Ride>,
        period: InsightPeriod,
        nowMillis: Long,
    ): RideInsights = calculate(rides, period, nowMillis, ZoneId.systemDefault())

    /**
     * Aggregates the rides falling in [period], relative to [nowMillis] in [zone].
     *
     * "Today" means since local midnight, which needs the zone; the other fixed periods are
     * rolling windows back from now. Each also defines an equally long preceding window,
     * which is what [RideInsights.distanceChangePercent] compares against.
     */
    fun calculate(
        rides: List<Ride>,
        period: InsightPeriod,
        nowMillis: Long,
        zone: ZoneId,
    ): RideInsights {
        val (currentStart, previousStart) = when (period) {
            InsightPeriod.Today -> {
                val todayStart = Instant.ofEpochMilli(nowMillis)
                    .atZone(zone)
                    .toLocalDate()
                    .atStartOfDay(zone)
                    .toInstant()
                    .toEpochMilli()
                Pair(todayStart, todayStart - MillisPerDay)
            }

            InsightPeriod.AllTime -> Pair(Long.MIN_VALUE, null)
            else -> {
                val window = (period.days ?: 0) * MillisPerDay
                val start = nowMillis - window
                Pair(start, start - window)
            }
        }

        val current = rides.filter { it.startedAtMillis in currentStart..nowMillis }
        if (current.isEmpty()) return RideInsights()

        val totalDuration = current.sumOf(Ride::durationMillis)
        val fuelEstimates = current.mapNotNull { ride ->
            ride.estimatedFuelLitres?.takeIf { it.isFinite() && it > 0.0 }
        }
        // Duration-weighted, not a plain mean of the per-ride averages: a five-minute
        // commute would otherwise pull the average speed as hard as a three-hour tour.
        val weightedSeconds = current.sumOf { it.durationMillis / 1_000.0 }.takeIf { it > 0.0 }
        // Null rather than 100% when the previous window holds no distance: there is no
        // meaningful percentage change from zero, and reporting one would be nonsense.
        val distanceChange = previousStart?.let { prevStart ->
            val previousDistance = rides
                .filter { it.startedAtMillis in prevStart..<currentStart }
                .sumOf(Ride::distanceKilometres)
            previousDistance.takeIf { it > 0.0 }?.let { previous ->
                ((current.sumOf(Ride::distanceKilometres) - previous) / previous) * 100.0
            }
        }

        return RideInsights(
            rideCount = current.size,
            totalDistanceKilometres = current.sumOf(Ride::distanceKilometres),
            totalDurationMillis = totalDuration,
            estimatedFuelLitres = fuelEstimates.sum().takeIf { fuelEstimates.isNotEmpty() },
            averageRideDistanceKilometres = current.map(Ride::distanceKilometres).average(),
            averageRideDurationMillis = totalDuration / current.size,
            averageSpeedKph = weightedSeconds?.let { seconds -> current.sumOf { it.averageSpeedKph * it.durationMillis / 1_000.0 } / seconds }
                ?: 0.0,
            averageRpm = weightedSeconds?.let { seconds -> current.sumOf { it.averageRpm * it.durationMillis / 1_000.0 } / seconds }
                ?: 0.0,
            averageThrottlePercent = weightedSeconds?.let { seconds -> current.sumOf { it.averageThrottlePercent * it.durationMillis / 1_000.0 } / seconds }
                ?: 0.0,
            averageMileageKilometresPerLitre = current.combinedMileageKilometresPerLitre(),
            longestRideKilometres = current.maxOf(Ride::distanceKilometres),
            highestSpeedKph = current.maxOf(Ride::maximumSpeedKph),
            distanceChangePercent = distanceChange,
            bestZeroToSixtyMillis = current.mapNotNull(Ride::zeroToSixtyMillis).minOrNull(),
            bestZeroToHundredMillis = current.mapNotNull(Ride::zeroToHundredMillis).minOrNull(),
            distanceTrendKilometres = current
                .sortedBy(Ride::startedAtMillis)
                .takeLast(DistanceTrendRides)
                .map(Ride::distanceKilometres),
        )
    }

    /**
     * Totals since the start of the current week. The week boundary follows [locale] the way the
     * rider's calendar does — Monday in most of the world, Sunday in some of it.
     */
    fun weekSummary(
        rides: List<Ride>,
        nowMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
    ): RideWeekSummary {
        val weekStart = Instant.ofEpochMilli(nowMillis)
            .atZone(zone)
            .toLocalDate()
            .with(WeekFields.of(locale).dayOfWeek(), 1L)
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()
        val week = rides.filter { it.startedAtMillis >= weekStart }
        if (week.isEmpty()) return RideWeekSummary()
        return RideWeekSummary(
            rideCount = week.size,
            distanceKilometres = week.sumOf(Ride::distanceKilometres),
            averageDurationMillis = week.sumOf(Ride::durationMillis) / week.size,
            mileageKilometresPerLitre = week.combinedMileageKilometresPerLitre(),
        )
    }

    private const val MillisPerDay = 86_400_000L

    /** Rides in the trend sparkline. Enough to show a shape, few enough to stay legible. */
    private const val DistanceTrendRides = 14
}
