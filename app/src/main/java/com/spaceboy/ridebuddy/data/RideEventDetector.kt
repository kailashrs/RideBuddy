package com.spaceboy.ridebuddy.data

object RideEventDetector {
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

    private const val MaximumEventSampleGapMillis = 2_500L
}
