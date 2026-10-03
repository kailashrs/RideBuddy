package com.spaceboy.ridebuddy.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import com.spaceboy.ridebuddy.data.Ride
import kotlinx.coroutines.flow.Flow

/** A named group of rides, such as a weekend tour, whose figures are read together. */
@Entity(tableName = "trips")
data class Trip(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAtMillis: Long,
)

/**
 * One ride in one trip. A ride can sit in more than one trip; deleting either side removes the
 * link, so a trip never counts a ride that is gone.
 */
@Entity(
    tableName = "trip_rides",
    primaryKeys = ["tripId", "rideId"],
    foreignKeys = [
        ForeignKey(entity = Trip::class, parentColumns = ["id"], childColumns = ["tripId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = Ride::class, parentColumns = ["id"], childColumns = ["rideId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("rideId")],
)
data class TripRide(val tripId: Long, val rideId: Long)

@Dao
interface TripDao {
    @Query("SELECT * FROM trips")
    fun observeTrips(): Flow<List<Trip>>

    @Query("SELECT * FROM trip_rides")
    fun observeTripRides(): Flow<List<TripRide>>

    @Insert
    suspend fun insert(trip: Trip): Long

    @Query("UPDATE trips SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("DELETE FROM trips WHERE id = :id")
    suspend fun delete(id: Long)

    @Insert
    suspend fun insertRides(rides: List<TripRide>)

    @Query("DELETE FROM trip_rides WHERE tripId = :tripId")
    suspend fun clearRides(tripId: Long)

    /** Names the trip and sets its rides in one step, creating it when [id] is null. */
    @Transaction
    suspend fun save(id: Long?, name: String, rideIds: Set<Long>, nowMillis: Long): Long {
        val tripId = id?.also { rename(it, name) } ?: insert(Trip(name = name, createdAtMillis = nowMillis))
        clearRides(tripId)
        insertRides(rideIds.map { TripRide(tripId, it) })
        return tripId
    }
}
