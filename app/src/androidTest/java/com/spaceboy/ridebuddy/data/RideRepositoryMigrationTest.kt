package com.spaceboy.ridebuddy.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
        val repository = RideRepository(context, databaseName = TestDatabaseName)

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
        val repository = RideRepository(context, databaseName = TestDatabaseName)
        repository.refresh()
        val legacy = repository.rides.value.single()
        assertEquals(2.5, legacy.distanceKilometres, 0.0)
        assertEquals(1_000L, legacy.averagingDurationMillis)
        assertEquals(1, repository.samples(1L).size)
        val id = repository.insert(legacy.copy(id = 0, telemetryDurationMillis = 750L))
        assertEquals(750L, repository.rides.value.first { it.id == id }.telemetryDurationMillis)
    }

    private companion object {
        const val TestDatabaseName = "rides-migration-test.db"
    }
}
