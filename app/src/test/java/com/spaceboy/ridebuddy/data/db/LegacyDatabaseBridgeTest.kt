package com.spaceboy.ridebuddy.data.db

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

// TEMPORARY, with LegacyDatabaseBridge: delete both together.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LegacyDatabaseBridgeTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test fun ridesMoveIntoRideBuddyDatabaseAndTheOldFileGoes() = runBlocking {
        // The pre-1.2 ride_history.db exactly as schema version 1 created it.
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath("ride_history.db"), null).use { legacy ->
            legacy.execSQL(LegacyRidesSql)
            legacy.execSQL(
                "INSERT INTO rides (id, startedAtMillis, endedAtMillis, distanceKilometres, averageSpeedKph, " +
                    "maximumSpeedKph, averageRpm, maximumRpm, averageThrottlePercent, routePreview, startArea, " +
                    "peakBrakingG) VALUES (42, 1000, 61000, 12.5, 30.0, 70.0, 4200.0, 8100, 25.0, '', 'Home', 0.6)",
            )
        }

        val database = RideBuddyDatabase.open(context)
        bridgeLegacyRides(context, database)

        val ride = database.rides().find(42)!!
        assertEquals(12.5, ride.distanceKilometres, 0.0)
        assertEquals("Home", ride.startArea)
        assertEquals(0.6, ride.peakBrakingG!!, 0.0)
        assertFalse(context.getDatabasePath("ride_history.db").exists())
        // A ride recorded afterwards does not collide with a carried-over id.
        assertEquals(43L, database.rides().insert(ride.copy(id = 0)))
        database.close()
    }

    @Test fun rawTelemetryMovesIntoTheRenamedFile() = runBlocking {
        val samples = byteArrayOf(1, 2, 3)
        Room.databaseBuilder(context, RawTelemetryDatabase::class.java, "ride_samples.db").build().also {
            it.samples().upsert(RideSampleSeries(7, 1000, samples))
            it.close()
        }

        val database = RawTelemetryDatabase.open(context)
        bridgeLegacyTelemetry(context, database)

        assertArrayEquals(samples, database.samples().data(7))
        assertFalse(context.getDatabasePath("ride_samples.db").exists())
        database.close()
    }

    private companion object {
        const val LegacyRidesSql = "CREATE TABLE IF NOT EXISTS `rides` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`startedAtMillis` INTEGER NOT NULL, `endedAtMillis` INTEGER NOT NULL, `distanceKilometres` REAL NOT NULL, " +
            "`averageSpeedKph` REAL NOT NULL, `maximumSpeedKph` REAL NOT NULL, `averageRpm` REAL NOT NULL, " +
            "`maximumRpm` INTEGER NOT NULL, `averageThrottlePercent` REAL NOT NULL, `estimatedFuelLitres` REAL, " +
            "`startArea` TEXT, `endArea` TEXT, `startLatitude` REAL, `startLongitude` REAL, `endLatitude` REAL, " +
            "`endLongitude` REAL, `routePreview` TEXT NOT NULL, `zeroToSixtyMillis` INTEGER, " +
            "`zeroToHundredMillis` INTEGER, `telemetryDurationMillis` INTEGER, `peakAccelerationG` REAL, " +
            "`peakBrakingG` REAL)"
    }
}
