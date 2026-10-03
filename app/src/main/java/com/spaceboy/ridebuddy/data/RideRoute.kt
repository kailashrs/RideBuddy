package com.spaceboy.ridebuddy.data

/** A route tolerates more detail than a chart before its shape stops improving. */
internal const val MaxRoutePoints = 1_000

/**
 * Where a ride went, as latitude and longitude pairs, at most [maxPoints] of them.
 *
 * Taken from the recorded samples while they are kept, and from the ride's thinned route preview
 * once retention has pruned them, so an older ride still has a shape to draw.
 */
internal fun rideRoute(ride: Ride, samples: List<RideSample>, maxPoints: Int): List<Pair<Double, Double>> {
    val recorded = samples.mapNotNull { sample ->
        sample.latitude?.let { latitude -> sample.longitude?.let { longitude -> latitude to longitude } }
    }
    return recorded.ifEmpty { ride.routePreview.map { it.latitude to it.longitude } }.downsampled(maxPoints)
}

/**
 * Every located ride of a trip, one route each and oldest first, sharing [MaxRoutePoints] between
 * them so a long trip draws no heavier than a single ride.
 */
internal suspend fun tripRoutes(rides: List<Ride>, samples: suspend (rideId: Long) -> List<RideSample>): List<List<Pair<Double, Double>>> {
    val perRide = (MaxRoutePoints / rides.size.coerceAtLeast(1)).coerceAtLeast(MinimumTripRoutePoints)
    return rides.map { rideRoute(it, samples(it.id), perRide) }.filter { it.size > 1 }
}

/** Below this a ride's line loses its corners. */
private const val MinimumTripRoutePoints = 100

/**
 * Evenly spaced subset of at most [maxPoints], preserving the first and last elements.
 *
 * Index arithmetic is done in `Long` because the intermediate product of the source index
 * and the target index overflows `Int` for a long ride's sample count.
 */
internal fun <T> List<T>.downsampled(maxPoints: Int): List<T> {
    if (size <= maxPoints) return this
    val sourceLastIndex = lastIndex.toLong()
    val targetLastIndex = maxPoints - 1L
    return List(maxPoints) { targetIndex ->
        this[(targetIndex.toLong() * sourceLastIndex / targetLastIndex).toInt()]
    }
}
