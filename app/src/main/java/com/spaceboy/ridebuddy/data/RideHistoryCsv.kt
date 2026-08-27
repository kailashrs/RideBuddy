package com.spaceboy.ridebuddy.data

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

private fun String.escapeCsv(): String = "\"${replace("\"", "\"\"")}\""
