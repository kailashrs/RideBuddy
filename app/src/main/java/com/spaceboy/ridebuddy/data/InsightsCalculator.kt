package com.spaceboy.ridebuddy.data

import java.time.Clock
import java.time.Instant
import java.time.ZoneId

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
        val weightedSeconds = current.sumOf { it.durationMillis / 1_000.0 }.takeIf { it > 0.0 }
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
        )
    }

    private const val MillisPerDay = 86_400_000L
}
