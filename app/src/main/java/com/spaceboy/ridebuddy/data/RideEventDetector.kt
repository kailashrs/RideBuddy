package com.spaceboy.ridebuddy.data

/**
 * Reduces a ride's samples to discrete hard-acceleration and hard-braking events.
 *
 * A single hard stop spans several consecutive samples over threshold. Reporting each as
 * its own event would make one stop look like five, so consecutive qualifying samples of
 * the same kind are collapsed into one episode, reported at its peak magnitude.
 */
object RideEventDetector {
    /**
     * Detects events in [samples], which must be in time order.
     *
     * An episode ends when a sample falls back under threshold, when the sign flips —
     * braking after accelerating is a new event, not a continuation — or when the gap to
     * the next qualifying sample is too long to be the same manoeuvre.
     */
    fun detect(samples: List<RideSample>, thresholdMetresPerSecondSquared: Double = 3.0): List<RideEvent> {
        val events = mutableListOf<RideEvent>()
        var activeEvent: RideEvent? = null
        var lastQualifyingSampleAtMillis: Long? = null

        fun finishActiveEvent() {
            activeEvent?.let(events::add)
            activeEvent = null
            lastQualifyingSampleAtMillis = null
        }

        samples.forEach { sample ->
            val type = when {
                sample.accelerationMetresPerSecondSquared >= thresholdMetresPerSecondSquared ->
                    RideEventType.HardAcceleration
                sample.accelerationMetresPerSecondSquared <= -thresholdMetresPerSecondSquared ->
                    RideEventType.HardBraking
                else -> null
            }
            if (type == null) {
                finishActiveEvent()
                return@forEach
            }

            val previousAt = lastQualifyingSampleAtMillis
            val continuesEpisode = activeEvent?.type == type && previousAt != null &&
                sample.timestampMillis - previousAt in 0..MaximumEventSampleGapMillis
            if (!continuesEpisode) {
                finishActiveEvent()
                activeEvent = sample.toRideEvent(type)
            } else if (kotlin.math.abs(sample.accelerationMetresPerSecondSquared) >
                kotlin.math.abs(activeEvent?.accelerationMetresPerSecondSquared ?: 0.0)
            ) {
                activeEvent = sample.toRideEvent(type)
            }
            lastQualifyingSampleAtMillis = sample.timestampMillis
        }

        finishActiveEvent()
        return events
    }

    private fun RideSample.toRideEvent(type: RideEventType) = RideEvent(
        timestampMillis,
        type,
        accelerationMetresPerSecondSquared,
    )

    /**
     * Beyond this gap, two qualifying samples are separate manoeuvres rather than one.
     * Generous enough to survive a few dropped telemetry frames mid-episode.
     */
    private const val MaximumEventSampleGapMillis = 2_500L
}
