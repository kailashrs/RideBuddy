package com.spaceboy.ridebuddy.ble

/** Applies the OEM live-display filter without changing raw or recorded telemetry. */
internal class LiveMileageSmoother(
    private val alpha: Double = DefaultAlpha,
) {
    private var filteredKilometresPerLitre: Double? = null

    init {
        require(alpha in 0.0..1.0 && alpha > 0.0)
    }

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
        const val DefaultAlpha = 0.2
    }
}
