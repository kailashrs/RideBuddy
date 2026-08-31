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

/**
 * Recomputes the live counters from scratch over the current sample window.
 *
 * Note this counts *samples* over threshold rather than episodes, so one sustained hard
 * braking contributes several. That is deliberate for a live intensity readout;
 * [RideEventDetector] does the episode-level analysis used for stored ride history.
 */
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
    intervals.sort()
    val baseline = intervals[intervals.size / 2].coerceAtLeast(1L)
    val expected = ((samples.last().timestampMillis - samples.first().timestampMillis) / baseline + 1L)
        .coerceAtLeast(1L)
    return (((expected - samples.size).coerceAtLeast(0L) * 100.0) / expected)
        .roundToInt()
        .coerceIn(0, 100)
}

private const val HardAccelerationThreshold = 3.0

/** Larger in magnitude: a bike brakes considerably harder than it accelerates. */
private const val HardBrakingThreshold = -3.5

/** Below this, the median interval is not a meaningful baseline. */
private const val MinimumPacketGapSamples = 4
