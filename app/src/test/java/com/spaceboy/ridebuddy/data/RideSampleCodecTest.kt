package com.spaceboy.ridebuddy.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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
    fun scalingRoundTripsWithinTheStoredPrecision() {
        assertEquals(87.4, 87.4.scaled(SpeedScale).unscaled(SpeedScale), 1e-9)
        assertEquals(-7.64, (-7.64).scaled(AccelerationScale).unscaled(AccelerationScale), 1e-9)
        assertEquals(12.9715937, 12.9715937.scaledOrNull(CoordinateScale).unscaledOrNull(CoordinateScale)!!, 1e-7)
    }

    @Test
    fun aMissingReadingStaysMissingRatherThanBecomingZero() {
        assertNull((null as Double?).scaledOrNull(MileageScale))
        assertNull((null as Long?).unscaledOrNull(MileageScale))
        // The vehicle reports no mileage on a closed throttle, which must not read as 0 km/L.
        assertTrue(0.0.scaledOrNull(MileageScale) == 0L)
    }

    @Test
    fun anInfiniteReadingIsStoredAsZeroRatherThanOverflowing() {
        assertEquals(0L, Double.NaN.scaled(SpeedScale))
        assertNull(Double.POSITIVE_INFINITY.scaledOrNull(SpeedScale))
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
