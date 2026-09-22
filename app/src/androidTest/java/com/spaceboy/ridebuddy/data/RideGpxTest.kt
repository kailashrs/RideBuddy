package com.spaceboy.ridebuddy.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.StringWriter
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.xml.sax.InputSource

@RunWith(AndroidJUnit4::class)
class RideGpxTest {
    @Test
    fun aPlaceNameHoldingMarkupStaysWellFormedAndReadsBackIntact() {
        val name = "Baiyappanahalli & Old <Madras> Road"
        val ride = ride().copy(startArea = name, endArea = "Electronic City")

        val gpx = write(ride, listOf(sample(12.9715937, 77.5945627)))

        // Concatenation would have emitted the ampersand raw and produced a broken document.
        assertTrue(gpx, "&amp;" in gpx)
        val document = parse(gpx)
        assertEquals(
            "$name to Electronic City",
            document.getElementsByTagName("name").item(0).textContent,
        )
    }

    @Test
    fun theTrackFallsBackToTheRideIdWhenNoPlaceWasResolved() {
        val gpx = write(ride(), listOf(sample(12.97, 77.59)))

        assertEquals("Ride 7", parse(gpx).getElementsByTagName("name").item(0).textContent)
    }

    @Test
    fun samplesWithoutAUsableFixAreSkippedRatherThanWrittenAsZeroes() {
        val gpx = write(
            ride(),
            listOf(
                sample(12.97, 77.59),
                sample(null, null),
                sample(Double.NaN, 77.59),
                sample(91.0, 77.59),
                sample(12.98, 181.0),
                sample(12.99, 77.60),
            ),
        )

        val points = parse(gpx).getElementsByTagName("trkpt")
        assertEquals(2, points.length)
        assertEquals("12.9700000", points.item(0).attributes.getNamedItem("lat").nodeValue)
    }

    @Test
    fun elevationIsWrittenOnlyForSamplesThatCarryIt() {
        val gpx = write(
            ride(),
            listOf(sample(12.97, 77.59, altitude = 912.25), sample(12.98, 77.60)),
        )

        assertEquals(1, parse(gpx).getElementsByTagName("ele").length)
        assertTrue(gpx, "912.25" in gpx)
    }

    private fun write(ride: Ride, samples: List<RideSample>) =
        StringWriter().apply { writeGpx(ride, samples) }.toString()

    private fun parse(xml: String) = DocumentBuilderFactory.newInstance()
        .newDocumentBuilder()
        .parse(InputSource(xml.reader()))

    private fun ride() = Ride(
        id = 7,
        startedAtMillis = 1_000,
        endedAtMillis = 2_000,
        distanceKilometres = 1.0,
        averageSpeedKph = 10.0,
        maximumSpeedKph = 20.0,
        averageRpm = 3_000.0,
        maximumRpm = 6_000,
        averageThrottlePercent = 15.0,
        estimatedFuelLitres = null,
    )

    private fun sample(latitude: Double?, longitude: Double?, altitude: Double? = null) = RideSample(
        timestampMillis = 1_500,
        speedKph = 36.0,
        rpm = 4_000,
        throttlePercent = 20,
        mileageKilometresPerLitre = null,
        accelerationMetresPerSecondSquared = 0.0,
        latitude = latitude,
        longitude = longitude,
        altitudeMetres = altitude,
    )
}
