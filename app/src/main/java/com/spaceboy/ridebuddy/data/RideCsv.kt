package com.spaceboy.ridebuddy.data

import java.io.Writer
import java.time.Instant

/**
 * Both CSV exports: the history summary, and one ride's sample series.
 *
 * Together, because they were separately hand-rolled and only one of them escaped anything.
 * The summary had to, since geocoded place names routinely contain commas; the sample export
 * happened to write nothing but numbers, so its missing escaping was invisible and would have
 * stayed invisible until the first text column was added to it. One writer, escaping always.
 *
 * Values are raw SI units and epoch-derived timestamps rather than the rider's display units,
 * so an export is reproducible regardless of the settings in force when it was taken.
 */
private const val HistoryHeader = "started_at,ended_at,start_area,end_area,distance_km,duration_ms," +
    "average_speed_kph,maximum_speed_kph,average_rpm,maximum_rpm," +
    "average_mileage_km_per_litre,estimated_fuel_l,zero_to_60_ms,zero_to_100_ms"

/**
 * The sample series.
 *
 * The acceleration column is the largest magnitude over each stored interval rather than an
 * instantaneous reading, which is what survives thinning — hence its name. See
 * [decimatedForStorage].
 */
private const val SampleHeader = "timestamp_iso,speed_kph,rpm,throttle_percent," +
    "mileage_km_per_litre,peak_acceleration_mps2,latitude,longitude,accuracy_m,altitude_m"

/** Serializes ride history for the share/export action. */
internal fun List<Ride>.toCsv(): String = buildString {
    appendLine(HistoryHeader)
    this@toCsv.forEach { ride ->
        appendLine(
            csvLine(
                ride.startedAtMillis,
                ride.endedAtMillis,
                ride.startArea,
                ride.endArea,
                ride.distanceKilometres,
                ride.durationMillis,
                ride.averageSpeedKph,
                ride.maximumSpeedKph,
                ride.averageRpm,
                ride.maximumRpm,
                ride.averageMileageKilometresPerLitre,
                ride.estimatedFuelLitres,
                ride.zeroToSixtyMillis,
                ride.zeroToHundredMillis,
            ),
        )
    }
}

/** Writes one ride's full sample series, streamed rather than assembled in memory. */
internal fun Writer.writeSampleCsv(samples: List<RideSample>) {
    appendLine(SampleHeader)
    samples.forEach { sample ->
        appendLine(
            csvLine(
                Instant.ofEpochMilli(sample.timestampMillis),
                sample.speedKph,
                sample.rpm,
                sample.throttlePercent,
                sample.mileageKilometresPerLitre,
                sample.accelerationMetresPerSecondSquared,
                sample.latitude,
                sample.longitude,
                sample.accuracyMetres,
                sample.altitudeMetres,
            ),
        )
    }
}

internal fun csvLine(vararg values: Any?): String = values.joinToString(",", transform = ::csvField)

/**
 * One field, quoted only when it has to be.
 *
 * Every value goes through the same rule rather than text taking one path and numbers
 * another, which is what keeps a field that starts life as a number safe if it ever stops
 * being one. A missing value is an empty column, never the string "null".
 *
 * RFC 4180: quote a field containing a comma, a quote or a line break, and double any quote
 * inside it. A line break inside quotes is legal and is preserved rather than stripped.
 */
private fun csvField(value: Any?): String {
    val text = value?.toString().orEmpty()
    if (text.none { it == '"' || it == ',' || it == '\n' || it == '\r' }) return text
    return "\"${text.replace("\"", "\"\"")}\""
}
