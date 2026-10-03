package com.spaceboy.ridebuddy.data

import androidx.room.Room
import android.database.sqlite.SQLiteDatabase
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.spaceboy.ridebuddy.data.db.RideBuddyDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TripRepositoryTest {
    private val database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), RideBuddyDatabase::class.java)
        .allowMainThreadQueries().build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val repository = TripRepository(database.trips(), scope) { 1_000L }

    @After fun close() { scope.cancel(); database.close() }

    private fun ride(startedAt: Long) = Ride(0, startedAt, startedAt + 3_600_000L, 40.0, 40.0, 80.0, 4_000.0, 7_000, 30.0, 1.0)

    private suspend fun trips() = database.trips().observeTrips().first().map { trip ->
        TripSummary(trip, database.trips().observeTripRides().first().filter { it.tripId == trip.id }.map { it.rideId }.toSet())
    }

    @Test fun a_trip_is_created_then_renamed_and_given_other_rides() = runBlocking {
        val (first, second, third) = listOf(1L, 2L, 3L).map { database.rides().insert(ride(it * 86_400_000L)) }

        val id = repository.save(null, "  Coastal run ", setOf(first, second))
        assertEquals(listOf(TripSummary(trips().single().trip, setOf(first, second))), trips())
        assertEquals("Coastal run", trips().single().trip.name)

        repository.save(id, "Coast and hills", setOf(second, third))
        assertEquals("Coast and hills", trips().single().trip.name)
        assertEquals(setOf(second, third), trips().single().rideIds)
    }

    @Test fun deleting_a_ride_takes_it_out_of_its_trips_and_deleting_a_trip_keeps_its_rides() = runBlocking {
        val (first, second) = listOf(1L, 2L).map { database.rides().insert(ride(it)) }
        val id = repository.save(null, "Weekend", setOf(first, second))

        database.rides().delete(first)
        assertEquals(setOf(second), trips().single().rideIds)

        repository.delete(id)
        assertEquals(emptyList<TripSummary>(), trips())
        assertEquals(second, database.rides().find(second)?.id)
    }

    @Test fun trips_sort_by_their_latest_ride_and_a_new_empty_one_by_when_it_was_made() {
        val rides = listOf(ride(5_000).copy(id = 1), ride(9_000).copy(id = 2))
        fun trip(id: Long, rideIds: Set<Long>, createdAt: Long = 0) =
            TripSummary(com.spaceboy.ridebuddy.data.db.Trip(id, "Trip $id", createdAt), rideIds)
        val sorted = listOf(trip(1, setOf(1)), trip(2, setOf(2)), trip(3, emptySet(), createdAt = 7_000)).sortedByLatestRide(rides)
        assertEquals(listOf(2L, 3L, 1L), sorted.map { it.trip.id })
        assertEquals(listOf(5_000L, 9_000L), trip(4, setOf(2, 1)).rides(rides).map { it.startedAtMillis })
    }

    @Test fun the_released_database_gains_trips_and_keeps_its_rides() = runBlocking {
        val file = RuntimeEnvironment.getApplication().getDatabasePath("released.db").apply { parentFile?.mkdirs(); delete() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            createReleasedSchema(db, version = 1)
            db.execSQL(
                "INSERT INTO rides (id, startedAtMillis, endedAtMillis, distanceKilometres, averageSpeedKph, " +
                    "maximumSpeedKph, averageRpm, maximumRpm, averageThrottlePercent, routePreview) " +
                    "VALUES (7, 1000, 2000, 12.5, 30.0, 60.0, 4000.0, 7000, 25.0, '')",
            )
        }
        // Room migrates on open and checks the result against the version 2 schema it was built with.
        val migrated = Room.databaseBuilder(RuntimeEnvironment.getApplication(), RideBuddyDatabase::class.java, file.path)
            .allowMainThreadQueries().build()
        try {
            assertEquals(12.5, migrated.rides().find(7)?.distanceKilometres)
            TripRepository(migrated.trips(), scope).save(null, "Ghats", setOf(7L))
            assertEquals(setOf(7L), migrated.trips().observeTripRides().first().map { it.rideId }.toSet())
        } finally {
            migrated.close()
        }
    }

    /** Builds the database exactly as the released build left it, from its exported schema. */
    private fun createReleasedSchema(db: SQLiteDatabase, version: Int) {
        val schema = Json.parseToJsonElement(
            File("schemas/com.spaceboy.ridebuddy.data.db.RideBuddyDatabase/$version.json").readText(),
        ).jsonObject.getValue("database").jsonObject
        schema.getValue("entities").jsonArray.forEach { entity ->
            val table = entity.jsonObject.getValue("tableName").jsonPrimitive.content
            db.execSQL(entity.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
            entity.jsonObject["indices"]?.jsonArray?.forEach { index ->
                db.execSQL(index.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
            }
        }
        schema.getValue("setupQueries").jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
        db.version = version
    }
}
