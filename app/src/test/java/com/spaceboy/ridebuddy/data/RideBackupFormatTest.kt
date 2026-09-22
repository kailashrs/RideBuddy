package com.spaceboy.ridebuddy.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RideBackupFormatTest {
    @Test
    fun aFullyPopulatedRideSurvivesTheRoundTrip() {
        val ride = ride().copy(
            estimatedFuelLitres = 1.37,
            startArea = "Koramangala",
            endArea = "Electronic City",
            startLatitude = 12.9352,
            startLongitude = 77.6245,
            endLatitude = 12.8452,
            endLongitude = 77.6602,
            routePreview = listOf(RoutePoint(12.9352, 77.6245), RoutePoint(12.8452, 77.6602)),
            zeroToSixtyMillis = 4_120,
            zeroToHundredMillis = 11_880,
            telemetryDurationMillis = 3_512_000,
        )

        val restored = decodeRideBackup(encodeRideBackup(listOf(ride))).single()

        // The id is reassigned by the database on restore and is deliberately not carried.
        assertEquals(ride.copy(id = 0), restored)
    }

    @Test
    fun everyOptionalFieldComesBackMissingRatherThanZeroed() {
        val restored = decodeRideBackup(encodeRideBackup(listOf(ride()))).single()

        assertNull(restored.estimatedFuelLitres)
        assertNull(restored.startArea)
        assertNull(restored.endArea)
        assertNull(restored.startLatitude)
        assertNull(restored.endLongitude)
        assertNull(restored.zeroToSixtyMillis)
        assertNull(restored.telemetryDurationMillis)
        assertTrue(restored.routePreview.isEmpty())
    }

    @Test
    fun aPlaceNameHoldingQuotesAndControlCharactersRoundTrips() {
        val awkward = "\"Kor\tamangala\"\nBengaluru\\Karnātaka — 8th Block"

        val restored = decodeRideBackup(encodeRideBackup(listOf(ride().copy(startArea = awkward)))).single()

        assertEquals(awkward, restored.startArea)
    }

    @Test
    fun anEmptyPlaceNameIsDistinctFromAMissingOne() {
        val restored = decodeRideBackup(encodeRideBackup(listOf(ride().copy(startArea = "")))).single()

        assertEquals("", restored.startArea)
        assertNull(restored.endArea)
    }

    @Test
    fun anUnrecognisedFileRestoresNothingRatherThanGuessing() {
        assertTrue(decodeRideBackup("").isEmpty())
        assertTrue(decodeRideBackup("not json at all").isEmpty())
        assertTrue(decodeRideBackup("""{"format":"something-else","version":1,"rides":[]}""").isEmpty())
        assertTrue(decodeRideBackup("""{"format":"ridebuddy-rides","version":99,"rides":[]}""").isEmpty())
    }

    @Test
    fun aMalformedEntryCostsOneRideRatherThanTheWholeHistory() {
        val encoded = JSONObject(encodeRideBackup(listOf(ride().copy(startedAtMillis = 1_000))))
        encoded.getJSONArray("rides")
            .put(JSONObject().put("endedAt", 5_000))
            .put("not even an object")
            .put(JSONObject(encodeRideBackup(listOf(ride().copy(startedAtMillis = 3_000)))).getJSONArray("rides").getJSONObject(0))

        val restored = decodeRideBackup(encoded.toString())

        assertEquals(listOf(1_000L, 3_000L), restored.map(Ride::startedAtMillis))
    }

    @Test
    fun aFieldAddedByALaterBuildIsIgnoredRatherThanRejected() {
        val encoded = JSONObject(encodeRideBackup(listOf(ride())))
        encoded.getJSONArray("rides").getJSONObject(0).put("leanAngleDegrees", 47.5)

        val restored = decodeRideBackup(encoded.toString())

        assertEquals(1, restored.size)
        assertEquals(42.5, restored.single().distanceKilometres, 1e-9)
    }

    @Test
    fun aFieldAnEarlierBuildNeverWroteFallsBackRatherThanFailing() {
        val encoded = JSONObject(encodeRideBackup(listOf(ride())))
        encoded.getJSONArray("rides").getJSONObject(0).remove("averageRpm")

        val restored = decodeRideBackup(encoded.toString())

        assertEquals(0.0, restored.single().averageRpm, 0.0)
        assertEquals(42.5, restored.single().distanceKilometres, 1e-9)
    }

    @Test
    fun anEmptyHistoryEncodesToSomethingThatStillParses() {
        assertTrue(decodeRideBackup(encodeRideBackup(emptyList())).isEmpty())
    }

    @Test
    fun aThousandRidesStayWellInsideTheBackupAllowance() {
        val rides = List(1_000) {
            ride().copy(
                startedAtMillis = it * 86_400_000L,
                startArea = "Koramangala, Bengaluru",
                endArea = "Electronic City, Bengaluru",
                routePreview = List(32) { point -> RoutePoint(12.97 + point * 0.001, 77.59 + point * 0.001) },
            )
        }

        val bytes = encodeRideBackup(rides).toByteArray().size

        // The backup service allows the whole app 25 MB.
        assertTrue("1,000 rides encoded to $bytes bytes", bytes < 2_000_000)
    }

    private fun ride() = Ride(
        id = 7,
        startedAtMillis = 1_758_500_000_000,
        endedAtMillis = 1_758_503_600_000,
        distanceKilometres = 42.5,
        averageSpeedKph = 41.9,
        maximumSpeedKph = 118.0,
        averageRpm = 5_240.5,
        maximumRpm = 9_800,
        averageThrottlePercent = 31.25,
        estimatedFuelLitres = null,
    )
}
