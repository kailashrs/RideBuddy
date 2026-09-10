package com.spaceboy.ridebuddy.data

/** Values retain their elapsed-time position, and nulls mark intervals that were not measured. */
internal data class TelemetryChartData(val values: List<Double?>, val timestampsMillis: List<Long>)

/** Retains both extrema in each bucket so downsampling does not hide a brief peak or stop. */
internal fun telemetryChartData(
    samples: List<RideSample>,
    maxPoints: Int = 600,
    value: (RideSample) -> Double,
): TelemetryChartData {
    require(maxPoints >= 4)
    if (samples.isEmpty()) return TelemetryChartData(emptyList(), emptyList())
    val selected = sortedSetOf(0, samples.lastIndex)
    val bucketSize = ((samples.size + maxPoints / 2 - 1) / (maxPoints / 2)).coerceAtLeast(1)
    samples.indices.chunked(bucketSize).forEach { indices ->
        indices.minByOrNull { value(samples[it]) }?.let(selected::add)
        indices.maxByOrNull { value(samples[it]) }?.let(selected::add)
    }
    // Locate gaps in the source, before thinning: a thinned interval is not a telemetry outage.
    val gaps = samples.indices.drop(1).filter { index ->
        samples[index].timestampMillis - samples[index - 1].timestampMillis !in 1..MaxDistanceIntegrationGapMillis
    }
    val values = mutableListOf<Double?>()
    val timestamps = mutableListOf<Long>()
    var gapIndex = 0
    var previousIndex = -1
    selected.forEach { index ->
        var crossesGap = false
        while (gapIndex < gaps.size && gaps[gapIndex] <= index) {
            val gap = gaps[gapIndex++]
            if (previousIndex >= 0 && gap > previousIndex) crossesGap = true
        }
        if (crossesGap) {
            values += null
            timestamps += samples[index].timestampMillis
        }
        values += value(samples[index]).takeIf(Double::isFinite)
        timestamps += samples[index].timestampMillis
        previousIndex = index
    }
    return TelemetryChartData(values, timestamps)
}
