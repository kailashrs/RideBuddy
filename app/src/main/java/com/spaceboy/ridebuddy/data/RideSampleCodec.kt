package com.spaceboy.ridebuddy.data

import kotlin.math.roundToLong

/**
 * Fixed-point scales for the stored sample columns.
 *
 * Every telemetry value is a measurement with a known useful precision, so each is stored as
 * a scaled integer rather than an IEEE double. SQLite writes an integer as a variable-length
 * value — one or two bytes for a speed, four or five for a coordinate — while a `REAL` is
 * always a full eight. Across a ride's samples that difference is most of the row.
 *
 * The scales are chosen to be finer than the source data: the vehicle reports speed in
 * whole km/h and mileage in tenths, and a GPS fix is never accurate to the centimetre that
 * [CoordinateScale] preserves. Nothing observable is rounded away.
 */
internal const val SpeedScale = 10.0

internal const val MileageScale = 10.0

internal const val AccelerationScale = 100.0

internal const val CoordinateScale = 10_000_000.0

internal const val MetresScale = 10.0

/** Scales and rounds for storage, keeping null as "no reading" rather than mapping it to zero. */
internal fun Double?.scaledOrNull(scale: Double): Long? =
    this?.takeIf(Double::isFinite)?.let { (it * scale).roundToLong() }

internal fun Double.scaled(scale: Double): Long = if (isFinite()) (this * scale).roundToLong() else 0L

internal fun Long?.unscaledOrNull(scale: Double): Double? = this?.let { it / scale }

internal fun Long.unscaled(scale: Double): Double = this / scale

/**
 * The interval stored samples are thinned to, from the roughly 250 ms the vehicle sends.
 *
 * Everything that reads the series back downsamples it much further — six hundred points for
 * a chart, a thousand for a route — so four samples a second were being written to disk and
 * then discarded on every read. One a second still gives an hour-long ride 3,600 points,
 * several times what any reader uses, and is the rate GPS traces are conventionally logged at.
 *
 * The exception is acceleration, which is why [RideSample.accelerationMetresPerSecondSquared]
 * is carried through thinning as the interval's extreme rather than the representative
 * sample's own value. See [decimatedForStorage].
 */
internal const val StoredSampleIntervalMillis = 1_000L

/**
 * Thins a completed ride's samples to one per [intervalMillis] for storage.
 *
 * The first sample of each interval is kept, so stored timestamps stay on the original
 * grid and a telemetry outage still shows as a gap rather than being closed up.
 *
 * Acceleration is treated differently from every other value. A hard stop is a brief,
 * high-magnitude excursion, and keeping whichever value happened to land on the retained
 * sample would flatten it — the ride-events list would lose exactly the episodes it exists
 * to report. The retained sample therefore carries the largest-magnitude acceleration seen
 * anywhere in its interval, which leaves [RideEventDetector] able to find the same episodes
 * at the same peaks it would have found at full rate.
 *
 * Runs on the ride's full-rate samples at save time, so the figures derived before storage —
 * the acceleration times and the route preview — are unaffected by any of this.
 */
internal fun List<RideSample>.decimatedForStorage(
    intervalMillis: Long = StoredSampleIntervalMillis,
): List<RideSample> {
    if (intervalMillis <= 0 || size < 2) return this
    val thinned = ArrayList<RideSample>(size / 4 + 1)
    var representative: RideSample? = null
    var peak = 0.0
    fun emit() {
        representative?.let { thinned += it.copy(accelerationMetresPerSecondSquared = peak) }
    }
    forEach { sample ->
        val current = representative
        if (current == null || sample.timestampMillis - current.timestampMillis >= intervalMillis) {
            emit()
            representative = sample
            peak = sample.accelerationMetresPerSecondSquared
        } else if (kotlin.math.abs(sample.accelerationMetresPerSecondSquared) > kotlin.math.abs(peak)) {
            peak = sample.accelerationMetresPerSecondSquared
        }
    }
    emit()
    return thinned
}
