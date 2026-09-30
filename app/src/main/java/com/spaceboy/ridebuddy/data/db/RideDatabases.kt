package com.spaceboy.ridebuddy.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Upsert
import com.spaceboy.ridebuddy.data.Ride
import com.spaceboy.ridebuddy.data.RoutePoint
import com.spaceboy.ridebuddy.data.decodeRoute
import com.spaceboy.ridebuddy.data.encode
import kotlinx.coroutines.flow.Flow

@Dao
interface RideDao {
    @Query("SELECT * FROM rides ORDER BY startedAtMillis DESC, id DESC")
    fun observeAll(): Flow<List<Ride>>

    @Query("SELECT * FROM rides WHERE id = :id")
    suspend fun find(id: Long): Ride?

    @Query("SELECT COUNT(*) FROM rides")
    suspend fun count(): Int

    @Insert
    suspend fun insert(ride: Ride): Long

    @Insert
    suspend fun insertAll(rides: List<Ride>)

    @Query("UPDATE rides SET startArea = :startArea, endArea = :endArea WHERE id = :id")
    suspend fun updateAreas(id: Long, startArea: String?, endArea: String?)

    @Query("DELETE FROM rides WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM rides")
    suspend fun deleteAll()
}

/**
 * One ride's sample series as a single compressed blob; see [encodeSampleSeries]. The start
 * time is repeated here so retention can prune without reaching into the other database.
 */
@Entity(tableName = "ride_samples")
class RideSampleSeries(
    @PrimaryKey val rideId: Long,
    val startedAtMillis: Long,
    val data: ByteArray,
)

@Dao
interface RideSampleDao {
    @Upsert
    suspend fun upsert(series: RideSampleSeries)

    @Upsert
    suspend fun upsertAll(series: List<RideSampleSeries>)

    @Query("SELECT data FROM ride_samples WHERE rideId = :rideId")
    suspend fun data(rideId: Long): ByteArray?

    @Query("SELECT COUNT(*) FROM ride_samples")
    suspend fun count(): Int

    @Query("DELETE FROM ride_samples WHERE rideId = :rideId")
    suspend fun delete(rideId: Long)

    @Query("DELETE FROM ride_samples")
    suspend fun deleteAll()

    @Query("DELETE FROM ride_samples WHERE startedAtMillis < :cutoffMillis")
    suspend fun deleteStartedBefore(cutoffMillis: Long): Int
}

internal class RouteConverter {
    @TypeConverter
    fun fromRoute(route: List<RoutePoint>): String = route.encode()

    @TypeConverter
    fun toRoute(value: String): List<RoutePoint> = value.decodeRoute()
}

/**
 * Ride summaries. Small, and the part Android's backup service carries — which copies the file
 * itself, so it is kept out of WAL mode where recent writes would sit in a separate log.
 */
@Database(entities = [Ride::class], version = 1, exportSchema = false)
@TypeConverters(RouteConverter::class)
abstract class RideHistoryDatabase : RoomDatabase() {
    abstract fun rides(): RideDao

    companion object {
        const val FileName = "ride_history.db"

        fun open(context: Context): RideHistoryDatabase =
            Room.databaseBuilder(context.applicationContext, RideHistoryDatabase::class.java, FileName)
                .setJournalMode(JournalMode.TRUNCATE)
                .build()
    }
}

/**
 * Sample series, kept in their own file so the backup rules can leave them out: they run to
 * megabytes per riding hour against the backup service's 25 MB allowance.
 */
@Database(entities = [RideSampleSeries::class], version = 1, exportSchema = false)
abstract class RideSamplesDatabase : RoomDatabase() {
    abstract fun samples(): RideSampleDao

    companion object {
        const val FileName = "ride_samples.db"

        fun open(context: Context): RideSamplesDatabase =
            Room.databaseBuilder(context.applicationContext, RideSamplesDatabase::class.java, FileName).build()
    }
}
