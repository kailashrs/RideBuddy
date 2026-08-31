package com.spaceboy.ridebuddy.ble

/**
 * Exponential moving average over the instantaneous mileage reading, for display only.
 *
 * The raw km/L figure swings hard with every throttle movement — unreadable as a live
 * number. Smoothing it with a low alpha gives the rider a figure that settles. The
 * filter is applied to the sampled live frame alone: raw telemetry, recorded ride
 * samples, and the fuel-consumption accumulation all keep the unfiltered value, so the
 * display choice never contaminates stored data.
 *
 * Instances are per-session; [reset] is called on disconnect so a new ride does not
 * inherit the tail of the previous one.
 */
internal class LiveMileageSmoother(
    private val alpha: Double = DefaultAlpha,
) {
    private var filteredKilometresPerLitre: Double? = null

    init {
        require(alpha in 0.0..1.0 && alpha > 0.0)
    }

    /**
     * Returns [frame] with its mileage replaced by the filtered value.
     *
     * A missing, non-finite or non-positive reading is passed through as null rather than
     * folded into the average: it means the vehicle reported nothing, and averaging it in
     * as a zero would drag the displayed figure down for the following several seconds.
     * The first usable sample seeds the average rather than being blended toward zero.
     */
    fun smooth(frame: TelemetryFrame): TelemetryFrame {
        val sample = frame.instantaneousMileageKilometresPerLitre
            ?.takeIf { it.isFinite() && it > 0.0 }
            ?: return frame.copy(instantaneousMileageKilometresPerLitre = null)
        val filtered = filteredKilometresPerLitre?.let { previous ->
            previous + alpha * (sample - previous)
        } ?: sample
        filteredKilometresPerLitre = filtered
        return frame.copy(instantaneousMileageKilometresPerLitre = filtered)
    }

    fun reset() {
        filteredKilometresPerLitre = null
    }

    private companion object {
        /** Weight given to each new sample. Low enough that the figure settles visibly. */
        const val DefaultAlpha = 0.2
    }
}
