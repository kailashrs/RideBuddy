package com.spaceboy.ridebuddy.data

import java.time.Clock

/**
 * Aggregates ride history for the insights screen.
 *
 * Pure and clock-injectable: every overload funnels into the one taking an explicit
 * timestamp, so period boundaries can be exercised directly.
 */
object InsightsCalculator {
    fun calculate(
        rides: List<Ride>,
        period: InsightPeriod,
        clock: Clock = Clock.systemDefaultZone(),
    ): RideInsights = calculate(rides, period, clock.millis())

    /**
     * Aggregates the rides falling in [period], relative to [nowMillis].
     *
     * Every fixed period is a rolling window back from now, so no zone is needed: a tab
     * labelled with a length covers exactly that length wherever the rider is. Each window
     * also defines an equally long preceding one, which is what
     * [RideInsights.distanceChangePercent] compares against.
     */
    fun calculate(
        rides: List<Ride>,
        period: InsightPeriod,
        nowMillis: Long,
    ): RideInsights {
        val (currentStart, previousStart) = when (period) {
            InsightPeriod.AllTime -> Pair(Long.MIN_VALUE, null)
            else -> {
                val window = (period.days ?: 0) * MillisPerDay
                val start = nowMillis - window
                Pair(start, start - window)
            }
        }

        val current = rides.filter { it.startedAtMillis in currentStart..nowMillis }
        if (current.isEmpty()) {
            val previousDistance = previousStart?.let { start ->
                rides.filter { it.startedAtMillis in start..<currentStart }.sumOf(Ride::distanceKilometres)
            } ?: 0.0
            return RideInsights(distanceChangePercent = if (previousDistance > 0.0) -100.0 else null)
        }

        val totalDuration = current.sumOf(Ride::durationMillis)
        val fuelEstimates = current.mapNotNull(Ride::estimatedFuelLitres)
        // Duration-weighted, not a plain mean of the per-ride averages: a five-minute
        // commute would otherwise pull the average speed as hard as a three-hour tour.
        val weightedSeconds = current.sumOf { it.averagingDurationMillis / 1_000.0 }.takeIf { it > 0.0 }
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

    /** Rides in the trend sparkline. Enough to show a shape, few enough to stay legible. */
    private const val DistanceTrendRides = 14
}
