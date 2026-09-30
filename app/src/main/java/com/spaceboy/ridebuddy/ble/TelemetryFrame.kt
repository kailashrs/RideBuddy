package com.spaceboy.ridebuddy.ble

import com.spaceboy.ridebuddy.domain.TelemetryFrame

/** Bytes 0..8 are the fields decoded here; firmware may append its own after them. */
private const val MinimumFrameLength = 9
private const val Header = 0x10
private const val KilometresPerLitrePerUnit = 0.2

/** Kotlin's `Byte` is signed; every field on this wire format is not. */
private val Byte.unsigned: Int
    get() = toInt() and 0xFF

/**
 * The telemetry wire format: a fixed little-endian layout behind a one-byte header.
 *
 * ```text
 * byte  0      0x10 header
 * bytes 1..2   front-wheel speed, raw * 0.01 km/h
 * byte  3      throttle opening, percentage-like
 * byte  4      instantaneous mileage, raw * 0.2 km/L
 * bytes 5..8   engine RPM, unsigned
 * bytes 9..n   firmware-specific trailer, ignored
 * ```
 *
 * Returns null when the payload is not a telemetry frame.
 * The length check is a minimum rather than an equality: firmware revisions
 * append their own trailing bytes after the documented fields, and rejecting
 * those would silently drop all telemetry on those clusters. The header check is
 * what actually distinguishes a telemetry frame from another payload arriving on
 * the same characteristic.
 */
internal fun parseTelemetryFrame(payload: ByteArray): TelemetryFrame? {
    if (payload.size < MinimumFrameLength || payload[0].unsigned != Header) return null

    val rawSpeed = payload[1].unsigned or (payload[2].unsigned shl 8)
    val rawRpm = payload[5].unsigned.toLong() or
        (payload[6].unsigned.toLong() shl 8) or
        (payload[7].unsigned.toLong() shl 16) or
        (payload[8].unsigned.toLong() shl 24)
    val rawMileage = payload[4].unsigned

    return TelemetryFrame(
        speedKilometresPerHour = rawSpeed * 0.01,
        throttlePercent = payload[3].unsigned,
        // An encoded zero is "no reading", not "zero km/L": consumers take the
        // reciprocal to show L/100 km, and zero has none. Surfacing it as null
        // lets the UI say "unavailable" instead of rendering an infinity.
        instantaneousMileageKilometresPerLitre = rawMileage
            .takeIf { it > 0 }
            ?.times(KilometresPerLitrePerUnit),
        engineRpm = rawRpm,
    )
}
