package com.spaceboy.ridebuddy.core.navigation

import java.net.URI
import java.net.URLDecoder

// Reading a shared Maps link. Pure text in, coordinates or a searchable place name out; no
// network, no Android. The network side of resolving a destination - expanding a short link
// and geocoding - lives in DestinationParser, which calls into this.

/**
 * Coordinates recoverable from the text without any network access.
 *
 * Tried against progressively more processed forms of the same input: the raw text, the
 * URL's decoded query parameter, and the whole link percent-decoded — a shared Maps link
 * carries its coordinates in any of the three depending on how it was shared. Degrees,
 * minutes and seconds come last because only a decoded link can contain them.
 */
internal fun directNavigationDestination(value: String): NavigationDestination? {
    val decoded = percentDecoded(value)
    return coordinateFromText(value)
        ?: coordinateFromText(extractNavigationQuery(value))
        ?: coordinateFromText(decoded)
        ?: degreesMinutesSecondsFrom(decoded)
        ?: mapsPathDestination(value)?.let { coordinateFromText(it) ?: degreesMinutesSecondsFrom(it) }
}

private fun coordinateFromText(value: String): NavigationDestination? {
    CoordinatePatterns.forEach { pattern ->
        pattern.find(value)?.let { match ->
            val latitude = match.groupValues[1].toDoubleOrNull() ?: return@let
            val longitude = match.groupValues[2].toDoubleOrNull() ?: return@let
            if (latitude in -90.0..90.0 && longitude in -180.0..180.0) {
                return NavigationDestination(latitude, longitude, "Shared destination")
            }
        }
    }
    return null
}

/** Percent-decoded text, or the input unchanged when it is not encoded or is malformed. */
private fun percentDecoded(value: String): String =
    runCatching { URLDecoder.decode(value, Charsets.UTF_8.name()) }.getOrDefault(value)

/**
 * A degrees/minutes/seconds pair, which is how Maps names a dropped pin or a saved parking
 * spot in the link's own path: `12°58'44.1"N 77°35'41.0"E`.
 */
private fun degreesMinutesSecondsFrom(value: String): NavigationDestination? {
    val match = DegreesMinutesSecondsPattern.find(value) ?: return null
    val latitude = sexagesimalDegrees(match.groupValues[1], match.groupValues[2], match.groupValues[3], match.groupValues[4])
        ?: return null
    val longitude = sexagesimalDegrees(match.groupValues[5], match.groupValues[6], match.groupValues[7], match.groupValues[8])
        ?: return null
    if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
    return NavigationDestination(latitude, longitude, "Shared destination")
}

private fun sexagesimalDegrees(degrees: String, minutes: String, seconds: String, hemisphere: String): Double? {
    val d = degrees.toDoubleOrNull() ?: return null
    val m = minutes.toDoubleOrNull() ?: return null
    val sec = seconds.toDoubleOrNull() ?: return null
    if (m >= 60.0 || sec >= 60.0) return null
    val magnitude = d + m / 60.0 + sec / 3_600.0
    return if (hemisphere == "S" || hemisphere == "W") -magnitude else magnitude
}

/**
 * The destination parameter out of a Maps-style URL, or the input unchanged when it is not
 * a URL — which is the normal case for a typed address.
 */
private fun extractNavigationQuery(value: String): String =
    queryParameters(value).let { it["destination"] ?: it["query"] ?: it["q"] } ?: value

private fun queryParameters(value: String): Map<String, String> = runCatching {
    URI(value).rawQuery.orEmpty().split('&').associate {
        val parts = it.split('=', limit = 2)
        parts.first() to URLDecoder.decode(parts.getOrElse(1) { "" }, Charsets.UTF_8.name())
    }
}.getOrDefault(emptyMap())

/**
 * What is worth handing to the geocoder, or null when the input is a link with no place in it.
 *
 * Never the link itself. Feeding a URL to the geocoder is how a shared place that carried no
 * coordinates used to fail: the geocoder cannot resolve a URL, so every such link came back
 * as "could not find that destination" whatever it pointed at. A Maps link names its place in
 * the path instead — `/maps/place/<name>/…` — and that name geocodes.
 */
internal fun geocodableText(value: String): String? {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return null
    val uri = runCatching { URI(trimmed) }.getOrNull()
    if (uri?.scheme == null) return trimmed

    val parameter = queryParameters(trimmed).let { it["destination"] ?: it["query"] ?: it["q"] }
    parameter?.trim()?.takeIf { it.isNotEmpty() }?.let { return it.removePrefix("loc:") }

    return mapsPathDestination(trimmed)
}

/**
 * The part of a Maps URL path that says where the rider is going, decoded.
 *
 * `/maps/dir/<origin>/<destination>` is what Google produces when a route is shared, and the
 * destination is the **last** of its stops. Taking the first would quietly navigate the rider
 * to where they already are — which looks like it worked, and is worse than an error.
 * `/maps/place/<name>` and `/maps/search/<query>` name their subject first instead.
 *
 * The `data=` blob and the `@lat,lon,17z` viewport are skipped: the first is not a place and
 * the second is only where the map happened to be centred.
 */
internal fun mapsPathDestination(value: String): String? {
    val uri = runCatching { URI(value) }.getOrNull() ?: return null
    val segments = uri.rawPath.orEmpty().split('/').filter { it.isNotBlank() }
    val anchor = segments.indexOfFirst { it == "dir" || it == "place" || it == "search" }
    if (anchor < 0) return null
    val stops = segments.drop(anchor + 1)
        .map { percentDecoded(it).replace('+', ' ').trim() }
        .filter { stop ->
            stop.isNotEmpty() &&
                !stop.startsWith("@") &&
                !stop.startsWith("data=") &&
                !stop.startsWith("!") &&
                !stop.contains('=')
        }
    if (stops.isEmpty()) return null
    return if (segments[anchor] == "dir") stops.last() else stops.first()
}

// Ordered by authority, not by position: Maps writes the place's own coordinates into the
// `data` blob as `!3d<lat>!4d<lon>`, while `@lat,lon` is only where the map was centred, so
// the former wins when both are present. Then coordinates carried in a query parameter or a
// `geo:` URI, then a bare pair on its own. The digit bounds keep them from matching arbitrary
// numbers elsewhere in a URL, and every match is range-checked before it is accepted.
private val CoordinatePatterns = listOf(
    Regex("!3d(-?\\d{1,2}(?:\\.\\d+)?)!4d(-?\\d{1,3}(?:\\.\\d+)?)"),
    Regex("@(-?\\d{1,2}(?:\\.\\d+)?),(-?\\d{1,3}(?:\\.\\d+)?)"),
    Regex("[?&](?:ll|sll|center)=(-?\\d{1,2}(?:\\.\\d+)?),(-?\\d{1,3}(?:\\.\\d+)?)"),
    Regex("(?:[?&](?:q|query|destination)=(?:loc:)?|geo:)(-?\\d{1,2}(?:\\.\\d+)?),(-?\\d{1,3}(?:\\.\\d+)?)"),
    Regex("^\\s*(-?\\d{1,2}(?:\\.\\d+)?)\\s*,\\s*(-?\\d{1,3}(?:\\.\\d+)?)\\s*$"),
)

/** `12°58'44.1"N 77°35'41.0"E`, the form Maps puts in a dropped pin's own path. */
private val DegreesMinutesSecondsPattern = Regex(
    "(\\d{1,3})\u00b0(\\d{1,2})'([\\d.]+)\"([NS])[^\\d]{1,4}(\\d{1,3})\u00b0(\\d{1,2})'([\\d.]+)\"([EW])",
)
