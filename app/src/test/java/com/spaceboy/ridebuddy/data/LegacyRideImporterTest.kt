package com.spaceboy.ridebuddy.data

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import com.spaceboy.ridebuddy.data.db.RideHistoryDatabase
import com.spaceboy.ridebuddy.data.db.RideSamplesDatabase
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LegacyRideImporterTest {
    private val context = RuntimeEnvironment.getApplication()
    private lateinit var legacyFile: File
    private lateinit var backupSnapshot: File
    private lateinit var history: RideHistoryDatabase
    private lateinit var samples: RideSamplesDatabase
    private lateinit var repository: RideRepository

    @Before
    fun setUp() {
        legacyFile = context.getDatabasePath("rides.db").also { it.parentFile?.mkdirs(); it.delete() }
        backupSnapshot = File(context.filesDir, "backup/rides.backup").also {
            it.parentFile?.mkdirs()
            it.writeText("old snapshot")
        }
        history = Room.inMemoryDatabaseBuilder(context, RideHistoryDatabase::class.java).allowMainThreadQueries().build()
        samples = Room.inMemoryDatabaseBuilder(context, RideSamplesDatabase::class.java).allowMainThreadQueries().build()
        repository = RideRepository(history, samples, CoroutineScope(SupervisorJob() + Dispatchers.Default))
    }

    @After
    fun tearDown() {
        history.close()
        samples.close()
        context.getDatabasePath("rides.db").parentFile?.listFiles()?.forEach(File::delete)
    }

    private fun importer() = LegacyRideImporter(legacyFile, backupSnapshot, history, samples)

    @Test
    fun `imports rides and samples from version 6, keeping ids, then retires the file`() = runBlocking {
        writeVersion6Database()

        val imported = importer().importIfPresent()

        assertEquals(3, imported)
        val ride = repository.ride(7)!!
        assertEquals(12.5, ride.distanceKilometres, 1e-9)
        assertEquals("Koramangala", ride.startArea)
        assertNull(ride.endArea)
        assertEquals(1.2, ride.estimatedFuelLitres!!, 1e-9)
        assertEquals(2, ride.routePreview.size)
        assertEquals(4_200L, ride.zeroToSixtyMillis)
        assertEquals(600_000L, ride.telemetryDurationMillis)

        val series = repository.samples(7)
        assertEquals(listOf(StartedAt, StartedAt + 1_000), series.map(RideSample::timestampMillis))
        assertEquals(42.3, series[1].speedKph, 1e-4)
        assertEquals(-3.25, series[1].accelerationMetresPerSecondSquared, 1e-4)
        assertEquals(24.6, series[1].mileageKilometresPerLitre!!, 1e-4)
        assertEquals(12.9715937, series[1].latitude!!, 1e-9)
        assertEquals(77.5945627, series[1].longitude!!, 1e-9)
        assertEquals(4.5f, series[1].accuracyMetres!!, 1e-4f)
        assertEquals(920.3, series[1].altitudeMetres!!, 1e-3)
        assertNull(series[0].latitude)
        assertNull(series[0].mileageKilometresPerLitre)

        // A ride whose samples had been pruned keeps its summary and has no series.
        assertTrue(repository.samples(8).isEmpty())
        // Orphaned samples of a deleted ride are dropped, as the old join dropped them.
        assertTrue(repository.samples(99).isEmpty())

        assertFalse(legacyFile.exists())
        assertTrue(File(legacyFile.path + LegacyRideImporter.RetiredSuffix).exists())
        assertFalse(backupSnapshot.exists())
    }

    @Test
    fun `a second run does nothing once the file is retired`() = runBlocking {
        writeVersion6Database()
        importer().importIfPresent()

        assertEquals(0, importer().importIfPresent())
        assertEquals(3, history.rides().count())
    }

    @Test
    fun `a database from another schema version is left untouched`() = runBlocking {
        writeVersion6Database(version = 5)

        assertEquals(0, importer().importIfPresent())

        assertEquals(0, history.rides().count())
        assertTrue(legacyFile.exists())
        assertTrue(backupSnapshot.exists())
    }

    @Test
    fun `existing history is never overwritten`() = runBlocking {
        writeVersion6Database()
        history.rides().insert(ride(id = 0, startedAt = 1L))

        assertEquals(0, importer().importIfPresent())

        assertEquals(1, history.rides().count())
        assertTrue(legacyFile.exists())
    }

    @Test
    fun `a failed import empties the new databases and keeps the legacy file`() = runBlocking {
        writeVersion6Database()
        SQLiteDatabase.openDatabase(legacyFile.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("DROP TABLE ride_samples")
        }

        val failure = runCatching { importer().importIfPresent() }

        assertTrue(failure.isFailure)
        assertTrue(backupSnapshot.exists())
        assertEquals(0, history.rides().count())
        assertEquals(0, samples.samples().count())
        assertTrue(legacyFile.exists())
    }

    /** The exact version 6 layout the pre-1.1 app wrote. */
    private fun writeVersion6Database(version: Int = 6) {
        SQLiteDatabase.openOrCreateDatabase(legacyFile, null).use { db ->
            db.execSQL(
                """CREATE TABLE rides (
                    id INTEGER PRIMARY KEY AUTOINCREMENT, started_at INTEGER NOT NULL,
                    ended_at INTEGER NOT NULL, distance_km REAL NOT NULL, average_speed REAL NOT NULL,
                    maximum_speed REAL NOT NULL, average_rpm REAL NOT NULL, maximum_rpm INTEGER NOT NULL,
                    average_throttle REAL NOT NULL, estimated_fuel_litres REAL, start_area TEXT,
                    end_area TEXT, start_latitude REAL, start_longitude REAL, end_latitude REAL,
                    end_longitude REAL, route_preview TEXT, zero_to_sixty INTEGER,
                    zero_to_hundred INTEGER, telemetry_duration INTEGER)""",
            )
            db.execSQL(
                """CREATE TABLE ride_samples (
                    ride_id INTEGER NOT NULL REFERENCES rides(id) ON DELETE CASCADE,
                    t INTEGER NOT NULL, speed INTEGER NOT NULL, rpm INTEGER NOT NULL,
                    throttle INTEGER NOT NULL, acceleration INTEGER NOT NULL, mileage INTEGER,
                    latitude INTEGER, longitude INTEGER, accuracy INTEGER, altitude INTEGER,
                    PRIMARY KEY (ride_id, t)) WITHOUT ROWID""",
            )
            db.execSQL(
                """INSERT INTO rides VALUES (7, $StartedAt, ${StartedAt + 600_000}, 12.5, 75.0, 110.2,
                    5200.0, 9100, 31.0, 1.2, 'Koramangala', NULL, 12.97, 77.59, 12.99, 77.61,
                    '12.97,77.59;12.99,77.61', 4200, NULL, 600000)""",
            )
            db.execSQL(
                """INSERT INTO rides VALUES (8, ${StartedAt + 86_400_000}, ${StartedAt + 87_000_000}, 3.0, 20.0,
                    40.0, 3000.0, 5000, 12.0, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL)""",
            )
            db.execSQL(
                """INSERT INTO rides VALUES (9, ${StartedAt + 2 * 86_400_000L}, ${StartedAt + 2 * 86_400_000L + 60_000},
                    1.0, 30.0, 45.0, 3500.0, 6000, 15.0, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, 60000)""",
            )
            db.execSQL("INSERT INTO ride_samples VALUES (7, 0, 0, 1200, 0, 0, NULL, NULL, NULL, NULL, NULL)")
            db.execSQL("INSERT INTO ride_samples VALUES (7, 1000, 423, 5400, 35, -325, 246, 129715937, 775945627, 45, 9203)")
            db.execSQL("INSERT INTO ride_samples VALUES (9, 0, 150, 3000, 10, 12, NULL, NULL, NULL, NULL, NULL)")
            db.execSQL("INSERT INTO ride_samples VALUES (99, 0, 150, 3000, 10, 12, NULL, NULL, NULL, NULL, NULL)")
            db.version = version
        }
    }

    private fun ride(id: Long, startedAt: Long) = Ride(id, startedAt, startedAt + 1, 1.0, 1.0, 1.0, 1.0, 1, 1.0, null)

    private companion object {
        const val StartedAt = 1_750_000_000_000L
    }
}
