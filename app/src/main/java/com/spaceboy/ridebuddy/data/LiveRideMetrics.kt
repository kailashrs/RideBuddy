package com.spaceboy.ridebuddy.data

import kotlin.math.roundToInt

/**
 * Live counters for the ride screen.
 *
 * [estimatedPacketGapPercent] is a link-quality figure: roughly what share of the expected
 * telemetry frames never arrived. Null until there are enough samples to estimate from.
 */
data class LiveRideMetrics(
    val hardAccelerationEvents: Int = 0,
    val hardBrakingEvents: Int = 0,
    val estimatedPacketGapPercent: Int? = null,
)

/** The live window and ride details count the same discrete maneuvers. */
internal fun calculateLiveRideMetrics(samples: List<RideSample>): LiveRideMetrics {
    val events = RideEventDetector.detect(samples)
    return LiveRideMetrics(
        hardAccelerationEvents = events.count { it.type == RideEventType.HardAcceleration },
        hardBrakingEvents = events.count { it.type == RideEventType.HardBraking },
        estimatedPacketGapPercent = estimatePacketGapPercent(samples),
    )
}

/**
 * Estimates what fraction of expected telemetry frames were lost.
 *
 * There is no sequence number on the wire, so loss is inferred from timing. The *median*
 * gap between samples is taken as the nominal frame interval — median rather than mean
 * because gaps caused by loss are exactly the outliers that would drag a mean upward and
 * hide the very thing being measured. Dividing the elapsed span by that interval gives how
 * many frames should have arrived, and the shortfall is the loss.
 */
private fun estimatePacketGapPercent(samples: List<RideSample>): Int? {
    if (samples.size < MinimumPacketGapSamples) return null
    val intervals = LongArray(samples.lastIndex) { index ->
        (samples[index + 1].timestampMillis - samples[index].timestampMillis).coerceAtLeast(1L)
    }
    if (samples.zipWithNext().any { (first, second) -> second.timestampMillis <= first.timestampMillis }) return null
    intervals.sort()
    val baseline = intervals[intervals.size / 2].coerceAtLeast(1L)
    val expected = ((samples.last().timestampMillis - samples.first().timestampMillis) / baseline + 1L)
        .coerceAtLeast(1L)
    return (((expected - samples.size).coerceAtLeast(0L) * 100.0) / expected)
        .roundToInt()
        .coerceIn(0, 100)
}

/** Below this, the median interval is not a meaningful baseline. */
private const val MinimumPacketGapSamples = 4

private const val StandardGravity = 9.80665

/** Weight of each new sample in the acceleration reading; the OEM app's own smoothing. */
private const val AccelerationSmoothing = 0.2

/**
 * The next smoothed longitudinal acceleration, in g, from wheel-speed change. Unclamped: the
 * gauge caps it at ±1 g for display, while ride peaks keep the real figure.
 */
internal fun nextAccelerationG(previousG: Double, sample: RideSample): Double =
    previousG + AccelerationSmoothing * (sample.accelerationMetresPerSecondSquared / StandardGravity - previousG)

/** A ride's strongest smoothed acceleration and braking, in g; braking as a positive figure. */
internal fun List<RideSample>.accelerationPeaks(): Pair<Double?, Double?> {
    var g = 0.0
    var peakAcceleration: Double? = null
    var peakBraking: Double? = null
    forEach { sample ->
        g = nextAccelerationG(g, sample)
        if (g > 0.0) peakAcceleration = maxOf(peakAcceleration ?: 0.0, g)
        if (g < 0.0) peakBraking = maxOf(peakBraking ?: 0.0, -g)
    }
    return peakAcceleration to peakBraking
}
