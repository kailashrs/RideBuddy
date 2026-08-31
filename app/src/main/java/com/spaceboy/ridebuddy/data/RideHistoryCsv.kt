package com.spaceboy.ridebuddy.data

/**
 * Column order for the exported CSV. Fixed and machine-readable: values are raw SI units
 * and epoch milliseconds rather than the rider's display units, so an export is
 * reproducible regardless of the settings in force when it was taken.
 */
private const val Header = "started_at,ended_at,start_area,end_area,distance_km,duration_ms," +
    "average_speed_kph,maximum_speed_kph,average_rpm,maximum_rpm," +
    "average_mileage_km_per_litre,estimated_fuel_l,zero_to_60_ms,zero_to_100_ms"

/** Serializes ride history for the share/export action. */
internal fun List<Ride>.toCsv(): String = buildString {
    appendLine(Header)
    this@toCsv.forEach { ride ->
        appendLine(
            listOf(
                ride.startedAtMillis,
                ride.endedAtMillis,
                ride.startArea.orEmpty().escapeCsv(),
                ride.endArea.orEmpty().escapeCsv(),
                ride.distanceKilometres,
                ride.durationMillis,
                ride.averageSpeedKph,
                ride.maximumSpeedKph,
                ride.averageRpm,
                ride.maximumRpm,
                ride.averageMileageKilometresPerLitre ?: "",
                ride.estimatedFuelLitres ?: "",
                ride.zeroToSixtyMillis ?: "",
                ride.zeroToHundredMillis ?: "",
            ).joinToString(","),
        )
    }
}

/**
 * Always quotes, and doubles any embedded quote. Area names come from the geocoder and
 * routinely contain commas, which would otherwise split a row into extra columns.
 */
private fun String.escapeCsv(): String = "\"${replace("\"", "\"\"")}\""
