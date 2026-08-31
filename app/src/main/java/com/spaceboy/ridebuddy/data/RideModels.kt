package com.spaceboy.ridebuddy.data

import androidx.compose.runtime.Immutable

/**
 * A completed ride, as stored and shown in history.
 *
 * Location fields are nullable throughout: a ride recorded without a GPS permission or
 * fix is still a valid ride, with distance and speed derived from vehicle telemetry alone.
 * [estimatedFuelLitres] is likewise nullable — it is accumulated from the vehicle's
 * reported mileage, which is not always available.
 */
@Immutable
data class Ride(
    val id: Long,
    val startedAtMillis: Long,
    val endedAtMillis: Long,
    val distanceKilometres: Double,
    val averageSpeedKph: Double,
    val maximumSpeedKph: Double,
    val averageRpm: Double,
    val maximumRpm: Long,
    val averageThrottlePercent: Double,
    val estimatedFuelLitres: Double?,
    val startArea: String? = null,
    val endArea: String? = null,
    val startLatitude: Double? = null,
    val startLongitude: Double? = null,
    val endLatitude: Double? = null,
    val endLongitude: Double? = null,
    val routePreview: List<RoutePoint> = emptyList(),
    val zeroToSixtyMillis: Long? = null,
    val zeroToHundredMillis: Long? = null,
) {
    /** Clamped at zero, so a clock adjustment mid-ride cannot produce a negative duration. */
    val durationMillis: Long get() = (endedAtMillis - startedAtMillis).coerceAtLeast(0)

    /** Distance over fuel, or null when either is missing or zero. */
    val averageMileageKilometresPerLitre: Double?
        get() {
            val distance = distanceKilometres.takeIf { it.isFinite() && it > 0.0 } ?: return null
            val fuel = estimatedFuelLitres?.takeIf { it.isFinite() && it > 0.0 } ?: return null
            return distance / fuel
        }
}

/**
 * Fleet mileage across several rides: total distance over total fuel, not the mean of the
 * per-ride figures. Averaging averages would weight a two-kilometre trip the same as a
 * two-hundred-kilometre one. Rides missing either figure are excluded from both totals.
 */
fun Iterable<Ride>.combinedMileageKilometresPerLitre(): Double? {
    val distanceAndFuel = mapNotNull { ride ->
        val distance = ride.distanceKilometres.takeIf { it.isFinite() && it > 0.0 }
            ?: return@mapNotNull null
        val fuel = ride.estimatedFuelLitres?.takeIf { it.isFinite() && it > 0.0 }
            ?: return@mapNotNull null
        distance to fuel
    }
    if (distanceAndFuel.isEmpty()) return null
    return distanceAndFuel.sumOf { it.first } / distanceAndFuel.sumOf { it.second }
}

/** One point of a stored route trace, thinned for the history preview map. */
data class RoutePoint(val latitude: Double, val longitude: Double)

/** Window the insights screen aggregates over. A null [days] means no lower bound. */
enum class InsightPeriod(val days: Int?) {
    Today(0),
    SevenDays(7),
    ThirtyDays(30),
    NinetyDays(90),
    AllTime(null),
}

/**
 * Aggregates for one [InsightPeriod]. Nullable fields are those with no meaningful value
 * when the period holds too little data — a change percentage needs a previous period to
 * compare against, and the acceleration bests need a ride that actually reached the speed.
 */
@Immutable
data class RideInsights(
    val rideCount: Int = 0,
    val totalDistanceKilometres: Double = 0.0,
    val totalDurationMillis: Long = 0,
    val estimatedFuelLitres: Double? = null,
    val averageRideDistanceKilometres: Double = 0.0,
    val averageRideDurationMillis: Long = 0,
    val averageSpeedKph: Double = 0.0,
    val averageRpm: Double = 0.0,
    val averageThrottlePercent: Double = 0.0,
    val averageMileageKilometresPerLitre: Double? = null,
    val longestRideKilometres: Double = 0.0,
    val highestSpeedKph: Double = 0.0,
    val distanceChangePercent: Double? = null,
    val bestZeroToSixtyMillis: Long? = null,
    val bestZeroToHundredMillis: Long? = null,
    /** Distance of the most recent rides in the period, oldest first, for the trend chart. */
    val distanceTrendKilometres: List<Double> = emptyList(),
)

/** Totals since the start of the current week, as the rider's locale defines the week. */
@Immutable
data class RideWeekSummary(
    val rideCount: Int = 0,
    val distanceKilometres: Double = 0.0,
    val averageDurationMillis: Long = 0,
    val mileageKilometresPerLitre: Double? = null,
)

/**
 * One recorded moment of a ride: vehicle telemetry, plus a GPS fix when one was available.
 *
 * [accelerationMetresPerSecondSquared] is derived from the change in wheel speed between
 * consecutive samples rather than measured, so it is longitudinal only.
 */
data class RideSample(
    val timestampMillis: Long,
    val speedKph: Double,
    val rpm: Long,
    val throttlePercent: Int,
    val mileageKilometresPerLitre: Double?,
    val accelerationMetresPerSecondSquared: Double,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracyMetres: Float? = null,
    val altitudeMetres: Double? = null,
)

enum class RideEventType { HardAcceleration, HardBraking }

/** One detected episode, timestamped at its peak. See [RideEventDetector]. */
data class RideEvent(
    val timestampMillis: Long,
    val type: RideEventType,
    val accelerationMetresPerSecondSquared: Double,
)
