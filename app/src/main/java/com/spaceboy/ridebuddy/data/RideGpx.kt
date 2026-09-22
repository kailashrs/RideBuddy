package com.spaceboy.ridebuddy.data

import android.util.Xml
import java.io.Writer
import java.time.Instant
import java.util.Locale

/**
 * One ride's route as a GPX 1.1 track.
 *
 * Built through the platform's XML serializer rather than by concatenating strings. While
 * every value written was a number the two were equivalent, but it is the serializer that
 * makes the track name safe to be real text — a place name like "Baiyappanahalli & Old
 * Madras Road" would have produced a malformed document, which is why the name used to be
 * the ride's id.
 *
 * Samples without a location are skipped rather than emitted as zeroes, which would draw a
 * line through the Gulf of Guinea. Coordinates are formatted with [Locale.US] because GPX
 * requires a dot decimal separator whatever the rider's locale is.
 */
private const val Namespace = "http://www.topografix.com/GPX/1/1"

internal fun Writer.writeGpx(ride: Ride, samples: List<RideSample>) {
    val gpx = Xml.newSerializer()
    gpx.setOutput(this)
    gpx.startDocument("UTF-8", true)
    // An empty prefix makes this the default namespace, so tags are written unqualified.
    gpx.setPrefix("", Namespace)
    gpx.startTag(Namespace, "gpx")
    gpx.attribute(null, "version", "1.1")
    gpx.attribute(null, "creator", "RideBuddy")
    gpx.startTag(Namespace, "trk")
    gpx.tag("name", ride.trackName())
    gpx.startTag(Namespace, "trkseg")
    samples.forEach { sample ->
        val latitude = sample.latitude ?: return@forEach
        val longitude = sample.longitude ?: return@forEach
        if (!latitude.isFinite() || latitude !in -90.0..90.0) return@forEach
        if (!longitude.isFinite() || longitude !in -180.0..180.0) return@forEach
        gpx.startTag(Namespace, "trkpt")
        gpx.attribute(null, "lat", String.format(Locale.US, "%.7f", latitude))
        gpx.attribute(null, "lon", String.format(Locale.US, "%.7f", longitude))
        sample.altitudeMetres?.takeIf(Double::isFinite)?.let {
            gpx.tag("ele", String.format(Locale.US, "%.2f", it))
        }
        gpx.tag("time", Instant.ofEpochMilli(sample.timestampMillis).toString())
        gpx.endTag(Namespace, "trkpt")
    }
    gpx.endTag(Namespace, "trkseg")
    gpx.endTag(Namespace, "trk")
    gpx.endTag(Namespace, "gpx")
    gpx.endDocument()
    gpx.flush()
}

/**
 * What the track is called in whatever the rider opens it with.
 *
 * Place names when the geocoder resolved them, because "Koramangala to Electronic City" is
 * what identifies a ride in a list of tracks; the id is the fallback, not the intent.
 */
private fun Ride.trackName(): String = when {
    startArea != null && endArea != null -> "$startArea to $endArea"
    startArea != null -> "Ride from $startArea"
    endArea != null -> "Ride to $endArea"
    else -> "Ride $id"
}

private fun org.xmlpull.v1.XmlSerializer.tag(name: String, text: String) {
    startTag(Namespace, name)
    text(text)
    endTag(Namespace, name)
}
