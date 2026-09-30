package com.spaceboy.ridebuddy.domain

/**
 * One decoded live-telemetry notification from the vehicle.
 *
 * Speed comes off the front wheel, so it reads zero while the bike is stationary even
 * with the engine running, and it is not corrected for wheel size.
 */
data class TelemetryFrame(
    val speedKilometresPerHour: Double,
    val throttlePercent: Int,
    /** Null when the vehicle reports no usable figure. */
    val instantaneousMileageKilometresPerLitre: Double?,
    val engineRpm: Long,
)

/**
 * A telemetry frame with both clocks attached.
 *
 * [receivedAtMillis] is wall-clock, for recording and display. [receivedAtElapsedRealtime]
 * is monotonic, and is the one to use for freshness: wall-clock time can jump backwards on
 * a time-zone or NTP correction, which would make a current reading look arbitrarily old
 * and trip a staleness check for reasons that have nothing to do with the bike.
 */
data class TelemetryReading(
    val frame: TelemetryFrame,
    val receivedAtMillis: Long,
    val receivedAtElapsedRealtime: Long,
)
