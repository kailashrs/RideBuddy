package com.spaceboy.ridebuddy.data

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
    val durationMillis: Long get() = (endedAtMillis - startedAtMillis).coerceAtLeast(0)

    val averageMileageKilometresPerLitre: Double?
        get() {
            val distance = distanceKilometres.takeIf { it.isFinite() && it > 0.0 } ?: return null
            val fuel = estimatedFuelLitres?.takeIf { it.isFinite() && it > 0.0 } ?: return null
            return distance / fuel
        }
}

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

data class RoutePoint(val latitude: Double, val longitude: Double)

enum class InsightPeriod(val days: Int?) {
    Today(0),
    SevenDays(7),
    ThirtyDays(30),
    NinetyDays(90),
    AllTime(null),
}

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
)

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

data class RideEvent(
    val timestampMillis: Long,
    val type: RideEventType,
    val accelerationMetresPerSecondSquared: Double,
)
