package com.spaceboy.ridebuddy.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class RideSampleCodecTest {
    @Test
    fun thinsTelemetryRateSamplesToOneASecond() {
        val ride = (0 until 40).map { sample(timestamp = it * 250L) }

        val stored = ride.decimatedForStorage()

        assertEquals(10, stored.size)
        assertEquals(listOf(0L, 1_000L, 2_000L, 3_000L, 4_000L), stored.take(5).map(RideSample::timestampMillis))
    }

    @Test
    fun aSeriesTooShortToThinIsReturnedUntouched() {
        val single = listOf(sample(timestamp = 0))

        assertSame(single, single.decimatedForStorage())
    }

    @Test
    fun theRetainedSampleCarriesTheIntervalsHardestBraking() {
        // The spike sits between two retained instants, which is exactly where thinning
        // would otherwise lose it.
        val ride = listOf(
            sample(timestamp = 0, acceleration = 0.2),
            sample(timestamp = 250, acceleration = -6.4),
            sample(timestamp = 500, acceleration = -1.0),
            sample(timestamp = 750, acceleration = 0.1),
            sample(timestamp = 1_000, acceleration = 0.3),
        )

        val stored = ride.decimatedForStorage()

        assertEquals(2, stored.size)
        assertEquals(-6.4, stored.first().accelerationMetresPerSecondSquared, 1e-9)
    }

    @Test
    fun thinningFindsTheSameEventsAtTheSamePeaksAsTheFullRateSeries() {
        val ride = buildList {
            repeat(60) { add(sample(timestamp = it * 250L, acceleration = 0.1)) }
            // A hard stop lasting a second and a half, peaking mid-episode.
            listOf(-3.2, -5.1, -7.6, -4.4, -3.4, -3.1).forEachIndexed { index, value ->
                add(sample(timestamp = 15_000L + index * 250L, acceleration = value))
            }
            repeat(20) { add(sample(timestamp = 16_500L + it * 250L, acceleration = 0.1)) }
        }

        val fullRate = RideEventDetector.detect(ride)
        val thinned = RideEventDetector.detect(ride.decimatedForStorage())

        assertEquals(1, fullRate.size)
        assertEquals(fullRate.map(RideEvent::type), thinned.map(RideEvent::type))
        assertEquals(
            fullRate.single().accelerationMetresPerSecondSquared,
            thinned.single().accelerationMetresPerSecondSquared,
            1e-9,
        )
    }

    @Test
    fun aTelemetryOutageStaysAGapRatherThanBeingClosedUp() {
        val ride = listOf(
            sample(timestamp = 0),
            sample(timestamp = 250),
            sample(timestamp = 30_000),
            sample(timestamp = 30_250),
        )

        val stored = ride.decimatedForStorage()

        assertEquals(listOf(0L, 30_000L), stored.map(RideSample::timestampMillis))
    }

    @Test
    fun aSeriesRoundTripsThroughTheStoredBlob() {
        val startedAt = 1_700_000_000_000L
        val samples = listOf(
            RideSample(startedAt, 0.0, 1_200, 0, null, 0.0),
            RideSample(
                startedAt + 1_000, 87.4, 7_650, 42, 24.6, -7.64,
                latitude = 12.9715937, longitude = 77.5945627, accuracyMetres = 4.5f, altitudeMetres = 920.3,
            ),
        )

        val decoded = decodeSampleSeries(startedAt, encodeSampleSeries(startedAt, samples))

        assertEquals(samples.map(RideSample::timestampMillis), decoded.map(RideSample::timestampMillis))
        assertEquals(87.4, decoded[1].speedKph, 1e-4)
        assertEquals(-7.64, decoded[1].accelerationMetresPerSecondSquared, 1e-4)
        assertEquals(12.9715937, decoded[1].latitude!!, 1e-9)
        assertEquals(77.5945627, decoded[1].longitude!!, 1e-9)
        assertEquals(920.3, decoded[1].altitudeMetres!!, 1e-3)
        assertEquals(7_650L, decoded[1].rpm)
        assertEquals(42, decoded[1].throttlePercent)
    }

    @Test
    fun aMissingReadingStaysMissingRatherThanBecomingZero() {
        val decoded = decodeSampleSeries(0, encodeSampleSeries(0, listOf(sample(timestamp = 0).copy(mileageKilometresPerLitre = null))))

        assertNull(decoded.single().mileageKilometresPerLitre)
        assertNull(decoded.single().latitude)
    }

    private fun sample(timestamp: Long, acceleration: Double = 0.0) = RideSample(
        timestampMillis = timestamp,
        speedKph = 40.0,
        rpm = 5_000,
        throttlePercent = 30,
        mileageKilometresPerLitre = 24.0,
        accelerationMetresPerSecondSquared = acceleration,
    )
}
