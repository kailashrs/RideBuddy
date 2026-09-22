package com.spaceboy.ridebuddy.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * The ride-summary snapshot Android's backup service carries.
 *
 * Only summaries are in it. A ride's samples run to megabytes an hour and the backup service
 * allows an app 25 MB in total, so the series can never be included; a summary is under a
 * kilobyte, which leaves room for tens of thousands of rides. Insights, records, weekly
 * totals and the history list are all computed from summaries alone, so what comes back from
 * a restore is every screen except a ride's detail charts.
 *
 * JSON, and named fields rather than positions, because a snapshot outlives the build that
 * wrote it. A field added later is simply absent from an older file and picks up its default,
 * and an older build reading a newer file ignores what it does not recognise — where a
 * positional format would have to reject the whole file on a field count and lose the
 * history. [Version] is for changes that names cannot absorb, such as a field changing units.
 *
 * `org.json` is a stub in `android.jar`, so the unit tests put the real artifact on their
 * classpath; see the `testImplementation` in the app's build script. On a device this is the
 * platform's own implementation and nothing extra is packaged.
 */
private const val Format = "ridebuddy-rides"

private const val Version = 1

internal fun encodeRideBackup(rides: List<Ride>): String = JSONObject().apply {
    put("format", Format)
    put("version", Version)
    put(
        "rides",
        JSONArray().apply {
            rides.forEach { ride -> put(ride.toJson()) }
        },
    )
}.toString()

/**
 * Reads a snapshot back.
 *
 * A restore runs unattended and cannot ask the rider what to do, so anything unreadable
 * returns nothing rather than a guess: the database stays empty and the app opens in its
 * first-run state instead of importing fields that mean something else. An entry that is
 * individually malformed costs that one ride and leaves the rest of the history intact.
 */
internal fun decodeRideBackup(text: String): List<Ride> {
    val root = runCatching { JSONObject(text) }.getOrNull() ?: return emptyList()
    if (root.optString("format") != Format) return emptyList()
    if (root.optInt("version", -1) != Version) return emptyList()
    val rides = root.optJSONArray("rides") ?: return emptyList()
    return buildList {
        for (index in 0 until rides.length()) {
            val entry = rides.optJSONObject(index) ?: continue
            add(entry.toRideOrNull() ?: continue)
        }
    }
}

private fun Ride.toJson(): JSONObject = JSONObject().apply {
    put("startedAt", startedAtMillis)
    put("endedAt", endedAtMillis)
    put("distanceKm", distanceKilometres)
    put("averageSpeedKph", averageSpeedKph)
    put("maximumSpeedKph", maximumSpeedKph)
    put("averageRpm", averageRpm)
    put("maximumRpm", maximumRpm)
    put("averageThrottlePercent", averageThrottlePercent)
    // put(String, Any?) omits a null key entirely, which is what "no reading" should look
    // like on the way back in.
    put("estimatedFuelLitres", estimatedFuelLitres)
    put("startArea", startArea)
    put("endArea", endArea)
    put("startLatitude", startLatitude)
    put("startLongitude", startLongitude)
    put("endLatitude", endLatitude)
    put("endLongitude", endLongitude)
    put("zeroToSixtyMillis", zeroToSixtyMillis)
    put("zeroToHundredMillis", zeroToHundredMillis)
    put("telemetryDurationMillis", telemetryDurationMillis)
    if (routePreview.isNotEmpty()) {
        put(
            "route",
            JSONArray().apply {
                routePreview.forEach { point ->
                    put(JSONArray().put(point.latitude).put(point.longitude))
                }
            },
        )
    }
}

/** Null when the entry lacks the two fields that make a ride a ride. */
private fun JSONObject.toRideOrNull(): Ride? {
    if (isNull("startedAt") || isNull("endedAt")) return null
    return Ride(
        id = 0,
        startedAtMillis = optLong("startedAt"),
        endedAtMillis = optLong("endedAt"),
        distanceKilometres = optDouble("distanceKm", 0.0),
        averageSpeedKph = optDouble("averageSpeedKph", 0.0),
        maximumSpeedKph = optDouble("maximumSpeedKph", 0.0),
        averageRpm = optDouble("averageRpm", 0.0),
        maximumRpm = optLong("maximumRpm", 0L),
        averageThrottlePercent = optDouble("averageThrottlePercent", 0.0),
        estimatedFuelLitres = optionalDouble("estimatedFuelLitres"),
        startArea = optionalString("startArea"),
        endArea = optionalString("endArea"),
        startLatitude = optionalDouble("startLatitude"),
        startLongitude = optionalDouble("startLongitude"),
        endLatitude = optionalDouble("endLatitude"),
        endLongitude = optionalDouble("endLongitude"),
        routePreview = optJSONArray("route").toRoutePoints(),
        zeroToSixtyMillis = optionalLong("zeroToSixtyMillis"),
        zeroToHundredMillis = optionalLong("zeroToHundredMillis"),
        telemetryDurationMillis = optionalLong("telemetryDurationMillis"),
    )
}

/** Skips any malformed point rather than failing: a bad preview must not lose the ride. */
private fun JSONArray?.toRoutePoints(): List<RoutePoint> {
    val array = this ?: return emptyList()
    return buildList {
        for (index in 0 until array.length()) {
            val pair = array.optJSONArray(index) ?: continue
            if (pair.length() < 2) continue
            val point = RoutePoint(pair.optDouble(0, Double.NaN), pair.optDouble(1, Double.NaN))
            if (point.isValid) add(point)
        }
    }
}

// isNull covers both an absent key and an explicit JSON null, so an optional field reads the
// same whether it was omitted on write or nulled by some later writer.

private fun JSONObject.optionalDouble(key: String): Double? = if (isNull(key)) null else optDouble(key)

private fun JSONObject.optionalLong(key: String): Long? = if (isNull(key)) null else optLong(key)

private fun JSONObject.optionalString(key: String): String? = if (isNull(key)) null else optString(key)
