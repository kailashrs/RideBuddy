package com.spaceboy.ridebuddy.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * A place the rider has navigated to or saved. One row covers both: [savedName] set means the
 * rider saved it, and [tripCount] counts routes actually started there, whether saved or not.
 */
@Entity(tableName = "destinations")
data class Destination(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val latitude: Double,
    val longitude: Double,
    /** The place's own name, or the first line of its address when it has none. */
    val placeName: String,
    val savedName: String? = null,
    val tripCount: Int = 0,
    val lastTripAtMillis: Long? = null,
) {
    val isSaved: Boolean get() = savedName != null
}

@Dao
interface DestinationDao {
    @Query("SELECT * FROM destinations")
    fun observeAll(): Flow<List<Destination>>

    @Query("SELECT * FROM destinations")
    suspend fun all(): List<Destination>

    @Insert
    suspend fun insert(destination: Destination): Long

    @Update
    suspend fun update(destination: Destination)

    @Query("UPDATE destinations SET savedName = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("DELETE FROM destinations WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM destinations WHERE id IN (:ids)")
    suspend fun delete(ids: List<Long>)

    /** Forgets trips: unsaved places go entirely, saved ones keep their name but lose their count. */
    @Query("DELETE FROM destinations WHERE savedName IS NULL")
    suspend fun deleteUnsaved()

    @Query("UPDATE destinations SET tripCount = 0, lastTripAtMillis = NULL")
    suspend fun resetTrips()
}
