package com.spaceboy.ridebuddy.data

object RideEventDetector {
    fun detect(samples: List<RideSample>, thresholdMetresPerSecondSquared: Double = 3.0): List<RideEvent> =
        samples.mapNotNull { sample ->
            when {
                sample.accelerationMetresPerSecondSquared >= thresholdMetresPerSecondSquared -> RideEvent(
                    sample.timestampMillis,
                    RideEventType.HardAcceleration,
                    sample.accelerationMetresPerSecondSquared,
                )
                sample.accelerationMetresPerSecondSquared <= -thresholdMetresPerSecondSquared -> RideEvent(
                    sample.timestampMillis,
                    RideEventType.HardBraking,
                    sample.accelerationMetresPerSecondSquared,
                )
                else -> null
            }
        }
}
