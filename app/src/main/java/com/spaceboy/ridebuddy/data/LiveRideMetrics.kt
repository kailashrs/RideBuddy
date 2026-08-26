package com.spaceboy.ridebuddy.data

import kotlin.math.roundToInt

data class LiveRideMetrics(
    val hardAccelerationEvents: Int = 0,
    val hardBrakingEvents: Int = 0,
    val estimatedPacketGapPercent: Int? = null,
)

internal fun calculateLiveRideMetrics(samples: List<RideSample>): LiveRideMetrics {
    var hardAccelerationEvents = 0
    var hardBrakingEvents = 0
    samples.forEach { sample ->
        when {
            sample.accelerationMetresPerSecondSquared >= HardAccelerationThreshold -> hardAccelerationEvents++
            sample.accelerationMetresPerSecondSquared <= HardBrakingThreshold -> hardBrakingEvents++
        }
    }

    return LiveRideMetrics(
        hardAccelerationEvents = hardAccelerationEvents,
        hardBrakingEvents = hardBrakingEvents,
        estimatedPacketGapPercent = estimatePacketGapPercent(samples),
    )
}

private fun estimatePacketGapPercent(samples: List<RideSample>): Int? {
    if (samples.size < MinimumPacketGapSamples) return null
    val intervals = LongArray(samples.lastIndex) { index ->
        (samples[index + 1].timestampMillis - samples[index].timestampMillis).coerceAtLeast(1L)
    }
    intervals.sort()
    val baseline = intervals[intervals.size / 2].coerceAtLeast(1L)
    val expected = ((samples.last().timestampMillis - samples.first().timestampMillis) / baseline + 1L)
        .coerceAtLeast(1L)
    return (((expected - samples.size).coerceAtLeast(0L) * 100.0) / expected)
        .roundToInt()
        .coerceIn(0, 100)
}

private const val HardAccelerationThreshold = 3.0
private const val HardBrakingThreshold = -3.5
private const val MinimumPacketGapSamples = 4
