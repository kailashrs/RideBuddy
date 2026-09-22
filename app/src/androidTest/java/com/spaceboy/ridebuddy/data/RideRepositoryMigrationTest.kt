package com.spaceboy.ridebuddy.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RideRepositoryMigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun createVersionOneDatabase() {
        context.deleteDatabase(TestDatabaseName)
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(TestDatabaseName), null).use { database ->
            database.execSQL(
                """CREATE TABLE rides (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    started_at INTEGER NOT NULL,
                    ended_at INTEGER NOT NULL,
                    distance_km REAL NOT NULL,
                    average_speed REAL NOT NULL,
                    maximum_speed REAL NOT NULL,
                    average_rpm REAL NOT NULL,
                    maximum_rpm INTEGER NOT NULL,
                    average_throttle REAL NOT NULL,
                    average_consumption REAL NOT NULL,
                    estimated_fuel REAL NOT NULL
                )""".trimIndent(),
            )
            database.execSQL(
                """INSERT INTO rides (
                    started_at, ended_at, distance_km, average_speed, maximum_speed,
                    average_rpm, maximum_rpm, average_throttle, average_consumption, estimated_fuel
                ) VALUES (1000, 2000, 2.5, 20.0, 40.0, 3500.0, 6000, 25.0, 4.0, 0.1)""".trimIndent(),
            )
            database.version = 1
        }
    }

    @After
    fun removeDatabase() {
        context.deleteDatabase(TestDatabaseName)
    }

    @Test
    fun legacySchemaIsResetBecauseFuelUnitsChanged() = runBlocking {
        val repository = RideRepository(context, databaseName = TestDatabaseName, backupStore = null)

        repository.refresh()

        assertTrue(repository.rides.value.isEmpty())

        val rideId = repository.insert(
            ride = Ride(
                id = 0,
                startedAtMillis = 3_000L,
                endedAtMillis = 4_000L,
                distanceKilometres = 10.0,
                averageSpeedKph = 36.0,
                maximumSpeedKph = 50.0,
                averageRpm = 4_000.0,
                maximumRpm = 6_000,
                averageThrottlePercent = 20.0,
                estimatedFuelLitres = 0.4,
            ),
            samples = listOf(
                RideSample(
                    timestampMillis = 3_500L,
                    speedKph = 36.0,
                    rpm = 4_000,
                    throttlePercent = 20,
                    mileageKilometresPerLitre = 25.0,
                    accelerationMetresPerSecondSquared = 0.0,
                ),
            ),
        )

        assertEquals(0.4, requireNotNull(repository.rides.value.single().estimatedFuelLitres), 0.0)
        assertEquals(25.0, requireNotNull(repository.samples(rideId).single().mileageKilometresPerLitre), 0.0)
    }

    @Test
    fun versionFourRidesAndSamplesSurviveTheTelemetryDurationMigration() = runBlocking {
        context.deleteDatabase(TestDatabaseName)
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(TestDatabaseName), null).use { database ->
            database.execSQL("""CREATE TABLE rides (
                id INTEGER PRIMARY KEY AUTOINCREMENT, started_at INTEGER NOT NULL, ended_at INTEGER NOT NULL,
                distance_km REAL NOT NULL, average_speed REAL NOT NULL, maximum_speed REAL NOT NULL,
                average_rpm REAL NOT NULL, maximum_rpm INTEGER NOT NULL, average_throttle REAL NOT NULL,
                estimated_fuel_litres REAL, start_area TEXT, end_area TEXT, start_latitude REAL,
                start_longitude REAL, end_latitude REAL, end_longitude REAL, route_preview TEXT,
                zero_to_sixty INTEGER, zero_to_hundred INTEGER
            )""")
            database.execSQL("""CREATE TABLE ride_samples (
                id INTEGER PRIMARY KEY AUTOINCREMENT, ride_id INTEGER NOT NULL REFERENCES rides(id) ON DELETE CASCADE,
                timestamp INTEGER NOT NULL, speed REAL NOT NULL, rpm INTEGER NOT NULL, throttle INTEGER NOT NULL,
                mileage_km_per_litre REAL, acceleration REAL NOT NULL, latitude REAL, longitude REAL,
                accuracy REAL, altitude REAL
            )""")
            database.execSQL("""INSERT INTO rides (id, started_at, ended_at, distance_km, average_speed,
                maximum_speed, average_rpm, maximum_rpm, average_throttle, estimated_fuel_litres)
                VALUES (1, 1000, 2000, 2.5, 20, 40, 3500, 6000, 25, 0.1)""")
            database.execSQL("""INSERT INTO ride_samples (ride_id, timestamp, speed, rpm, throttle, mileage_km_per_litre, acceleration)
                VALUES (1, 1500, 20, 3500, 25, 25, 0)""")
            database.version = 4
        }
        val repository = RideRepository(context, databaseName = TestDatabaseName, backupStore = null)
        repository.refresh()
        val legacy = repository.rides.value.single()
        assertEquals(2.5, legacy.distanceKilometres, 0.0)
        assertEquals(1_000L, legacy.averagingDurationMillis)
        assertEquals(1, repository.samples(1L).size)
        val id = repository.insert(legacy.copy(id = 0, telemetryDurationMillis = 750L))
        assertEquals(750L, repository.rides.value.first { it.id == id }.telemetryDurationMillis)
    }


    @Test
    fun versionFiveSamplesAreRewrittenIntoTheCompactLayoutWithoutLosingAnything() = runBlocking {
        context.deleteDatabase(TestDatabaseName)
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(TestDatabaseName), null).use { database ->
            database.execSQL(
                """CREATE TABLE rides (
                id INTEGER PRIMARY KEY AUTOINCREMENT, started_at INTEGER NOT NULL, ended_at INTEGER NOT NULL,
                distance_km REAL NOT NULL, average_speed REAL NOT NULL, maximum_speed REAL NOT NULL,
                average_rpm REAL NOT NULL, maximum_rpm INTEGER NOT NULL, average_throttle REAL NOT NULL,
                estimated_fuel_litres REAL, start_area TEXT, end_area TEXT, start_latitude REAL,
                start_longitude REAL, end_latitude REAL, end_longitude REAL, route_preview TEXT,
                zero_to_sixty INTEGER, zero_to_hundred INTEGER, telemetry_duration INTEGER
            )""",
            )
            database.execSQL(
                """CREATE TABLE ride_samples (
                id INTEGER PRIMARY KEY AUTOINCREMENT, ride_id INTEGER NOT NULL REFERENCES rides(id) ON DELETE CASCADE,
                timestamp INTEGER NOT NULL, speed REAL NOT NULL, rpm INTEGER NOT NULL, throttle INTEGER NOT NULL,
                mileage_km_per_litre REAL, acceleration REAL NOT NULL, latitude REAL, longitude REAL,
                accuracy REAL, altitude REAL
            )""",
            )
            database.execSQL("CREATE INDEX samples_ride_time ON ride_samples(ride_id, timestamp)")
            database.execSQL(
                """INSERT INTO rides (id, started_at, ended_at, distance_km, average_speed, maximum_speed,
                average_rpm, maximum_rpm, average_throttle, estimated_fuel_litres, telemetry_duration)
                VALUES (1, 1000, 5000, 2.5, 20, 40, 3500, 6000, 25, 0.1, 4000)""",
            )
            database.execSQL(
                """INSERT INTO ride_samples (ride_id, timestamp, speed, rpm, throttle,
                mileage_km_per_litre, acceleration, latitude, longitude, accuracy, altitude)
                VALUES (1, 1500, 20.4, 3500, 25, 25.5, -6.44, 12.9715937, 77.5945627, 4.5, 901.3)""",
            )
            database.execSQL(
                """INSERT INTO ride_samples (ride_id, timestamp, speed, rpm, throttle,
                mileage_km_per_litre, acceleration) VALUES (1, 1750, 21.0, 3600, 30, NULL, 0.5)""",
            )
            database.version = 5
        }

        val repository = RideRepository(context, databaseName = TestDatabaseName, backupStore = null)
        repository.refresh()

        assertEquals(2.5, repository.rides.value.single().distanceKilometres, 0.0)
        val samples = repository.samples(1L)
        assertEquals(2, samples.size)
        // Absolute timestamps come back although the column now stores an offset.
        assertEquals(1_500L, samples.first().timestampMillis)
        assertEquals(20.4, samples.first().speedKph, 1e-9)
        assertEquals(-6.44, samples.first().accelerationMetresPerSecondSquared, 1e-9)
        assertEquals(25.5, requireNotNull(samples.first().mileageKilometresPerLitre), 1e-9)
        assertEquals(12.9715937, requireNotNull(samples.first().latitude), 1e-7)
        assertEquals(901.3, requireNotNull(samples.first().altitudeMetres), 1e-6)
        // A reading the vehicle never sent stays missing rather than becoming zero.
        assertNull(samples[1].mileageKilometresPerLitre)
        assertNull(samples[1].latitude)
    }

    @Test
    fun pruningDropsOldSamplesAndKeepsEveryRide() = runBlocking {
        context.deleteDatabase(TestDatabaseName)
        val repository = RideRepository(context, databaseName = TestDatabaseName, backupStore = null)
        repository.refresh()
        val old = repository.insert(ride(startedAt = 1_000L), listOf(sample(1_100L)))
        val recent = repository.insert(ride(startedAt = 500_000L), listOf(sample(500_100L)))

        val removed = repository.pruneSamplesStartedBefore(400_000L)

        assertEquals(1, removed)
        assertEquals(2, repository.rides.value.size)
        assertTrue(repository.samples(old).isEmpty())
        assertEquals(1, repository.samples(recent).size)
    }

    @Test
    fun aRestoreBringsBackSummariesOnlyWhenThereIsNoLocalHistory() = runBlocking {
        context.deleteDatabase(TestDatabaseName)
        val store = RideBackupStore(context, directory = File(context.cacheDir, "backup-test").apply { deleteRecursively() })
        store.write(listOf(ride(startedAt = 1_000L).copy(startArea = "Koramangala")))

        val repository = RideRepository(context, databaseName = TestDatabaseName, backupStore = store)
        assertEquals(1, repository.restoreFromBackupIfEmpty())
        assertEquals("Koramangala", repository.rides.value.single().startArea)
        // Samples are never in a snapshot; the restored ride has its summary and nothing more.
        assertTrue(repository.samples(repository.rides.value.single().id).isEmpty())

        // A second pass must not duplicate what is already there.
        assertEquals(0, repository.restoreFromBackupIfEmpty())
        assertEquals(1, repository.rides.value.size)
    }

    @Test
    fun savingARideRefreshesTheSnapshotTheBackupServicePicksUp() = runBlocking {
        context.deleteDatabase(TestDatabaseName)
        val store = RideBackupStore(context, directory = File(context.cacheDir, "backup-test").apply { deleteRecursively() })
        val repository = RideRepository(context, databaseName = TestDatabaseName, backupStore = store)
        repository.refresh()

        repository.insert(ride(startedAt = 2_000L), listOf(sample(2_100L)))

        assertEquals(listOf(2_000L), store.read().map(Ride::startedAtMillis))
    }

    @Test
    fun deletingOneRideTakesItsSamplesAndLeavesTheRest() = runBlocking {
        context.deleteDatabase(TestDatabaseName)
        val store = RideBackupStore(context, directory = File(context.cacheDir, "backup-test").apply { deleteRecursively() })
        val repository = RideRepository(context, databaseName = TestDatabaseName, backupStore = store)
        repository.refresh()
        val doomed = repository.insert(ride(startedAt = 1_000L), listOf(sample(1_100L)))
        val kept = repository.insert(ride(startedAt = 2_000L), listOf(sample(2_100L)))

        repository.delete(doomed)

        assertEquals(listOf(kept), repository.rides.value.map(Ride::id))
        assertTrue(repository.samples(doomed).isEmpty())
        assertEquals(1, repository.samples(kept).size)
        // The snapshot the backup service reads must not keep offering a deleted ride.
        assertEquals(listOf(2_000L), store.read().map(Ride::startedAtMillis))
    }

    private fun ride(startedAt: Long) = Ride(
        id = 0,
        startedAtMillis = startedAt,
        endedAtMillis = startedAt + 60_000L,
        distanceKilometres = 10.0,
        averageSpeedKph = 36.0,
        maximumSpeedKph = 50.0,
        averageRpm = 4_000.0,
        maximumRpm = 6_000,
        averageThrottlePercent = 20.0,
        estimatedFuelLitres = 0.4,
    )

    private fun sample(timestamp: Long) = RideSample(
        timestampMillis = timestamp,
        speedKph = 36.0,
        rpm = 4_000,
        throttlePercent = 20,
        mileageKilometresPerLitre = 25.0,
        accelerationMetresPerSecondSquared = 0.0,
    )

    private companion object {
        const val TestDatabaseName = "rides-migration-test.db"
    }
}
