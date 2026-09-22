package com.spaceboy.ridebuddy.data

import android.content.ContentValues
import android.content.Context
import android.util.Log
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.database.sqlite.SQLiteStatement
import androidx.core.database.sqlite.transaction
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal const val DefaultRideDatabaseName = "rides.db"

/**
 * Stores ride history in a local SQLite database and publishes it as a flow.
 *
 * Nothing leaves the device by itself: history, samples and route traces are local, the
 * export actions are the only way any of it moves, and the [backupStore] snapshot is written
 * for Android's own backup service rather than sent anywhere by this app.
 *
 * All access is serialised by a mutex and dispatched to IO. The mutex is not about SQLite —
 * which handles its own locking — but about the flow: without it, two concurrent writers
 * could interleave their re-reads and publish a list that lags the database.
 */
class RideRepository(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    databaseName: String = DefaultRideDatabaseName,
    private val backupStore: RideBackupStore? = RideBackupStore(context),
) {
    private val database = RideDatabase(context.applicationContext, databaseName)
    private val databaseMutex = Mutex()
    private val mutableRides = MutableStateFlow<List<Ride>>(emptyList())
    val rides: StateFlow<List<Ride>> = mutableRides.asStateFlow()

    /** Reloads history from the database into [rides]. */
    suspend fun refresh() = withContext(ioDispatcher) {
        databaseMutex.withLock {
            mutableRides.value = database.readRides()
        }
    }

    /**
     * Stores a ride with its samples in one transaction and returns its new id. The
     * published list is refreshed as part of the same locked section.
     */
    suspend fun insert(ride: Ride, samples: List<RideSample> = emptyList()): Long = withContext(ioDispatcher) {
        databaseMutex.withLock {
            val rideId = database.insertRide(ride, samples)
            try {
                publishLocked(database.readRides())
            } catch (error: Exception) {
                // The transaction already committed. Report the successful insert even if a
                // history refresh fails, or the retained-save queue would insert it twice.
                Log.w(LogTag, "Ride saved but history could not be refreshed", error)
                publishLocked(
                    (mutableRides.value + ride.copy(id = rideId)).sortedByDescending { it.startedAtMillis },
                )
            }
            rideId
        }
    }

    /** Fills in place labels once reverse geocoding has resolved them, after the insert. */
    suspend fun updateAreas(rideId: Long, startArea: String?, endArea: String?) = withContext(ioDispatcher) {
        databaseMutex.withLock {
            database.updateAreas(rideId, startArea, endArea)
            publishLocked(database.readRides())
        }
    }

    /**
     * Full sample series for one ride, loaded on demand. Deliberately not part of [Ride]:
     * a ride can hold tens of thousands of samples, and the history list needs none of them.
     *
     * Empty for a ride whose samples have aged out under the rider's retention setting. The
     * ride itself, its summary figures and its route preview are kept indefinitely.
     */
    suspend fun samples(rideId: Long): List<RideSample> = withContext(ioDispatcher) {
        databaseMutex.withLock { database.readSamples(rideId) }
    }

    /**
     * Drops the sample series of every ride that started before [cutoffMillis], and returns
     * how many samples went.
     *
     * Only the samples go. The ride row stays, so history, insights, records and the route
     * preview are unaffected however far back they reach — what a rider loses is the detail
     * charts and the per-sample exports for rides older than they asked to keep.
     */
    suspend fun pruneSamplesStartedBefore(cutoffMillis: Long): Int = withContext(ioDispatcher) {
        databaseMutex.withLock { database.pruneSamplesStartedBefore(cutoffMillis) }
    }

    /**
     * Deletes one ride. Its samples go with it via the foreign key's cascade.
     *
     * Unlike retention, which keeps the ride and drops only its detail, this removes the ride
     * from history entirely — so its distance, fuel and records stop counting towards every
     * total the Insights screen shows. That is the point of it, and why it is confirmed.
     */
    suspend fun delete(rideId: Long) = withContext(ioDispatcher) {
        databaseMutex.withLock {
            database.writableDatabase.delete(RideDatabase.Table, "id = ?", arrayOf(rideId.toString()))
            publishLocked(database.readRides())
        }
    }

    /** Deletes all history. Samples go with it via the foreign key's cascade. */
    suspend fun clear() = withContext(ioDispatcher) {
        databaseMutex.withLock {
            database.writableDatabase.delete(RideDatabase.Table, null, null)
            publishLocked(emptyList())
        }
    }

    /**
     * Restores summaries from the backup snapshot when there is no local history.
     *
     * Android's backup service restores the snapshot file before the app first runs, so this
     * is checked at startup. An empty database is the one state where importing cannot
     * destroy anything, which is why it is the only state that imports — a rider who has
     * ridden since the restore keeps what they recorded.
     *
     * Samples are not in the snapshot and cannot be; they are far past the backup service's
     * 25 MB budget. Restored rides therefore carry their summary and route preview, and no
     * detail charts.
     */
    suspend fun restoreFromBackupIfEmpty(): Int = withContext(ioDispatcher) {
        val store = backupStore ?: return@withContext 0
        databaseMutex.withLock {
            if (database.hasAnyRide()) return@withLock 0
            val restored = store.read()
            if (restored.isEmpty()) return@withLock 0
            database.insertRestoredRides(restored)
            mutableRides.value = database.readRides()
            restored.size
        }
    }

    /**
     * Publishes history and refreshes the snapshot the backup service picks up.
     *
     * The snapshot is rewritten on every mutation rather than on a timer: it is under a
     * megabyte for a thousand rides, rides are saved a handful of times a day, and a stale
     * snapshot is worse than a redundant write. A failure here is logged and otherwise
     * ignored, because losing a backup must never fail the save that triggered it.
     */
    private fun publishLocked(rides: List<Ride>) {
        mutableRides.value = rides
        val store = backupStore ?: return
        try {
            store.write(rides)
        } catch (error: Exception) {
            Log.w(LogTag, "Ride history saved but the backup snapshot could not be written", error)
        }
    }

    private companion object {
        const val LogTag = "RideRepository"
    }
}

/**
 * Schema and queries. Plain SQLite rather than an ORM: two tables, a handful of statements,
 * and no need for a code-generation dependency.
 */
private class RideDatabase(context: Context, name: String) : SQLiteOpenHelper(context, name, null, Version) {
    /** Off by default on Android, and the samples table's cascade delete depends on it. */
    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE $Table (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                started_at INTEGER NOT NULL,
                ended_at INTEGER NOT NULL,
                distance_km REAL NOT NULL,
                average_speed REAL NOT NULL,
                maximum_speed REAL NOT NULL,
                average_rpm REAL NOT NULL,
                maximum_rpm INTEGER NOT NULL,
                average_throttle REAL NOT NULL,
                estimated_fuel_litres REAL,
                start_area TEXT,
                end_area TEXT,
                start_latitude REAL,
                start_longitude REAL,
                end_latitude REAL,
                end_longitude REAL,
                route_preview TEXT,
                zero_to_sixty INTEGER,
                zero_to_hundred INTEGER,
                telemetry_duration INTEGER
            )""".trimIndent(),
        )
        createSamplesTable(db)
    }

    /**
     * Preserves rides and samples from any schema that already used the current fuel units.
     *
     * Version 4 is brought up to 5 by adding its missing column, and 5 to 6 by rewriting the
     * samples into the compact layout. Anything older predates the fuel-unit change, so its
     * stored figures are not comparable with today's and are discarded rather than migrated
     * into history as though they meant the same thing.
     */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion !in 4..5) {
            recreate(db)
            return
        }
        if (oldVersion == 4) db.execSQL("ALTER TABLE $Table ADD COLUMN telemetry_duration INTEGER")
        migrateSamplesToCompactLayout(db)
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = recreate(db)

    /**
     * Rewrites the version-5 samples table into the compact layout.
     *
     * Values are scaled and timestamps rebased onto each ride's start in SQL, so a long
     * history does not have to be pulled through the heap to be converted. The join drops
     * any sample whose ride is gone, and `OR REPLACE` covers the one case the new primary
     * key does not tolerate: two legacy samples recorded in the same millisecond.
     *
     * Migrated rides keep their original rate. Only rides recorded from here on are thinned,
     * which leaves the existing ones exactly as detailed as they were.
     */
    private fun migrateSamplesToCompactLayout(db: SQLiteDatabase) = db.transaction {
        execSQL("ALTER TABLE $SamplesTable RENAME TO $LegacySamplesTable")
        createSamplesTable(this)
        execSQL(
            """INSERT OR REPLACE INTO $SamplesTable (
                ride_id, t, speed, rpm, throttle, acceleration, mileage,
                latitude, longitude, accuracy, altitude
            )
            SELECT s.ride_id,
                s.timestamp - r.started_at,
                CAST(ROUND(s.speed * $SpeedScale) AS INTEGER),
                s.rpm,
                s.throttle,
                CAST(ROUND(s.acceleration * $AccelerationScale) AS INTEGER),
                CAST(ROUND(s.mileage_km_per_litre * $MileageScale) AS INTEGER),
                CAST(ROUND(s.latitude * $CoordinateScale) AS INTEGER),
                CAST(ROUND(s.longitude * $CoordinateScale) AS INTEGER),
                CAST(ROUND(s.accuracy * $MetresScale) AS INTEGER),
                CAST(ROUND(s.altitude * $MetresScale) AS INTEGER)
            FROM $LegacySamplesTable s JOIN $Table r ON r.id = s.ride_id""".trimIndent(),
        )
        execSQL("DROP TABLE $LegacySamplesTable")
    }

    /**
     * Inserts a ride and its samples atomically, so a failure partway cannot leave a ride
     * with a truncated trace.
     *
     * Samples go through one compiled, rebound statement rather than per-row inserts: a
     * ride can carry thousands, and re-parsing the SQL for each one dominates.
     */
    fun insertRide(ride: Ride, samples: List<RideSample>): Long {
        val db = writableDatabase
        return db.transaction {
            val rideId = db.insertOrThrow(Table, null, ride.toContentValues())
            db.compileStatement(InsertSampleSql).use { statement ->
                samples.forEach { sample ->
                    statement.clearBindings()
                    statement.bindLong(1, rideId)
                    // Stored relative to the ride's start: an offset is a one- or two-byte
                    // integer where an epoch millisecond is always six.
                    statement.bindLong(2, sample.timestampMillis - ride.startedAtMillis)
                    statement.bindLong(3, sample.speedKph.scaled(SpeedScale))
                    statement.bindLong(4, sample.rpm)
                    statement.bindLong(5, sample.throttlePercent.toLong())
                    statement.bindLong(6, sample.accelerationMetresPerSecondSquared.scaled(AccelerationScale))
                    statement.bindNullableLong(7, sample.mileageKilometresPerLitre.scaledOrNull(MileageScale))
                    statement.bindNullableLong(8, sample.latitude.scaledOrNull(CoordinateScale))
                    statement.bindNullableLong(9, sample.longitude.scaledOrNull(CoordinateScale))
                    statement.bindNullableLong(10, sample.accuracyMetres?.toDouble().scaledOrNull(MetresScale))
                    statement.bindNullableLong(11, sample.altitudeMetres.scaledOrNull(MetresScale))
                    // Two samples in one millisecond would collide on the primary key.
                    // Keeping the later of the pair is right and costs nothing.
                    statement.executeInsert()
                }
            }
            rideId
        }
    }

    /** Re-inserts restored summaries, letting SQLite assign fresh ids. */
    fun insertRestoredRides(rides: List<Ride>) {
        val db = writableDatabase
        db.transaction {
            rides.forEach { ride -> db.insertOrThrow(Table, null, ride.toContentValues()) }
        }
    }

    fun hasAnyRide(): Boolean = readableDatabase.rawQuery("SELECT 1 FROM $Table LIMIT 1", null)
        .use(Cursor::moveToFirst)

    fun pruneSamplesStartedBefore(cutoffMillis: Long): Int = writableDatabase.delete(
        SamplesTable,
        "ride_id IN (SELECT id FROM $Table WHERE started_at < ?)",
        arrayOf(cutoffMillis.toString()),
    )

    fun updateAreas(rideId: Long, startArea: String?, endArea: String?) {
        writableDatabase.update(
            Table,
            ContentValues().apply {
                if (startArea == null) putNull("start_area") else put("start_area", startArea)
                if (endArea == null) putNull("end_area") else put("end_area", endArea)
            },
            "id = ?",
            arrayOf(rideId.toString()),
        )
    }

    // Column indices are resolved once before each read loop rather than per row, since
    // getColumnIndexOrThrow is a name lookup and these loops run over every stored sample.

    /**
     * One ride's samples, with absolute timestamps and real units restored.
     *
     * The ride's start is read first because stored offsets are relative to it. A ride that
     * has since been deleted has no samples to return.
     */
    fun readSamples(rideId: Long): List<RideSample> {
        val startedAt = readableDatabase.query(
            Table, arrayOf("started_at"), "id = ?", arrayOf(rideId.toString()), null, null, null,
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else return emptyList() }
        return readableDatabase.query(
            SamplesTable, null, "ride_id = ?", arrayOf(rideId.toString()), null, null, "t ASC",
        ).use { cursor ->
            val offset = cursor.getColumnIndexOrThrow("t")
            val speed = cursor.getColumnIndexOrThrow("speed")
            val rpm = cursor.getColumnIndexOrThrow("rpm")
            val throttle = cursor.getColumnIndexOrThrow("throttle")
            val acceleration = cursor.getColumnIndexOrThrow("acceleration")
            val mileage = cursor.getColumnIndexOrThrow("mileage")
            val latitude = cursor.getColumnIndexOrThrow("latitude")
            val longitude = cursor.getColumnIndexOrThrow("longitude")
            val accuracy = cursor.getColumnIndexOrThrow("accuracy")
            val altitude = cursor.getColumnIndexOrThrow("altitude")
            buildList(cursor.count) {
                while (cursor.moveToNext()) add(
                    RideSample(
                        timestampMillis = startedAt + cursor.getLong(offset),
                        speedKph = cursor.getLong(speed).unscaled(SpeedScale),
                        rpm = cursor.getLong(rpm),
                        throttlePercent = cursor.getInt(throttle),
                        mileageKilometresPerLitre = cursor.nullableLong(mileage).unscaledOrNull(MileageScale),
                        accelerationMetresPerSecondSquared =
                            cursor.getLong(acceleration).unscaled(AccelerationScale),
                        latitude = cursor.nullableLong(latitude).unscaledOrNull(CoordinateScale),
                        longitude = cursor.nullableLong(longitude).unscaledOrNull(CoordinateScale),
                        accuracyMetres = cursor.nullableLong(accuracy).unscaledOrNull(MetresScale)?.toFloat(),
                        altitudeMetres = cursor.nullableLong(altitude).unscaledOrNull(MetresScale),
                    ),
                )
            }
        }
    }

    /**
     * The compact samples layout.
     *
     * `WITHOUT ROWID` with the ride and offset as the key stores each row inside the index
     * that every sample query already uses, instead of alongside a separate copy of the same
     * two columns — the table and its index were previously holding the pair twice. There is
     * no surrogate id because nothing ever addressed a sample by one.
     *
     * `acceleration` is kept rather than derived from adjacent speeds because thinning makes
     * it the interval's extreme rather than a difference between neighbours. See
     * [decimatedForStorage].
     */
    private fun createSamplesTable(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS $SamplesTable (
                ride_id INTEGER NOT NULL REFERENCES $Table(id) ON DELETE CASCADE,
                t INTEGER NOT NULL,
                speed INTEGER NOT NULL,
                rpm INTEGER NOT NULL,
                throttle INTEGER NOT NULL,
                acceleration INTEGER NOT NULL,
                mileage INTEGER,
                latitude INTEGER,
                longitude INTEGER,
                accuracy INTEGER,
                altitude INTEGER,
                PRIMARY KEY (ride_id, t)
            ) WITHOUT ROWID""".trimIndent(),
        )
    }

    /** All rides, newest first — the order the history screen displays them in. */
    fun readRides(): List<Ride> = readableDatabase.query(
        Table,
        null,
        null,
        null,
        null,
        null,
        "started_at DESC",
    ).use { cursor ->
        val id = cursor.getColumnIndexOrThrow("id")
        val startedAt = cursor.getColumnIndexOrThrow("started_at")
        val endedAt = cursor.getColumnIndexOrThrow("ended_at")
        val distance = cursor.getColumnIndexOrThrow("distance_km")
        val averageSpeed = cursor.getColumnIndexOrThrow("average_speed")
        val maximumSpeed = cursor.getColumnIndexOrThrow("maximum_speed")
        val averageRpm = cursor.getColumnIndexOrThrow("average_rpm")
        val maximumRpm = cursor.getColumnIndexOrThrow("maximum_rpm")
        val averageThrottle = cursor.getColumnIndexOrThrow("average_throttle")
        val estimatedFuel = cursor.getColumnIndexOrThrow("estimated_fuel_litres")
        val startArea = cursor.getColumnIndexOrThrow("start_area")
        val endArea = cursor.getColumnIndexOrThrow("end_area")
        val startLatitude = cursor.getColumnIndexOrThrow("start_latitude")
        val startLongitude = cursor.getColumnIndexOrThrow("start_longitude")
        val endLatitude = cursor.getColumnIndexOrThrow("end_latitude")
        val endLongitude = cursor.getColumnIndexOrThrow("end_longitude")
        val routePreview = cursor.getColumnIndexOrThrow("route_preview")
        val zeroToSixty = cursor.getColumnIndexOrThrow("zero_to_sixty")
        val zeroToHundred = cursor.getColumnIndexOrThrow("zero_to_hundred")
        val telemetryDuration = cursor.getColumnIndexOrThrow("telemetry_duration")
        buildList(cursor.count) {
            while (cursor.moveToNext()) {
                add(
                    Ride(
                        id = cursor.getLong(id),
                        startedAtMillis = cursor.getLong(startedAt),
                        endedAtMillis = cursor.getLong(endedAt),
                        distanceKilometres = cursor.getDouble(distance),
                        averageSpeedKph = cursor.getDouble(averageSpeed),
                        maximumSpeedKph = cursor.getDouble(maximumSpeed),
                        averageRpm = cursor.getDouble(averageRpm),
                        maximumRpm = cursor.getLong(maximumRpm),
                        averageThrottlePercent = cursor.getDouble(averageThrottle),
                        estimatedFuelLitres = cursor.nullableDouble(estimatedFuel),
                        startArea = cursor.nullableString(startArea),
                        endArea = cursor.nullableString(endArea),
                        startLatitude = cursor.nullableDouble(startLatitude),
                        startLongitude = cursor.nullableDouble(startLongitude),
                        endLatitude = cursor.nullableDouble(endLatitude),
                        endLongitude = cursor.nullableDouble(endLongitude),
                        routePreview = cursor.nullableString(routePreview).decodeRoute(),
                        zeroToSixtyMillis = cursor.nullableLong(zeroToSixty),
                        zeroToHundredMillis = cursor.nullableLong(zeroToHundred),
                        telemetryDurationMillis = cursor.nullableLong(telemetryDuration),
                    ),
                )
            }
        }
    }

    companion object {
        const val Table = "rides"
        const val SamplesTable = "ride_samples"
        const val LegacySamplesTable = "ride_samples_legacy"
        const val Version = 6
        private const val InsertSampleSql = """INSERT OR REPLACE INTO $SamplesTable (
            ride_id, t, speed, rpm, throttle, acceleration, mileage,
            latitude, longitude, accuracy, altitude
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"""
    }

    private fun recreate(db: SQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS $SamplesTable")
        db.execSQL("DROP TABLE IF EXISTS $LegacySamplesTable")
        db.execSQL("DROP TABLE IF EXISTS $Table")
        onCreate(db)
    }
}

/** Column values for a ride row. The id is left to SQLite in both insert paths. */
private fun Ride.toContentValues(): ContentValues = ContentValues().apply {
    put("started_at", startedAtMillis)
    put("ended_at", endedAtMillis)
    put("distance_km", distanceKilometres)
    put("average_speed", averageSpeedKph)
    put("maximum_speed", maximumSpeedKph)
    put("average_rpm", averageRpm)
    put("maximum_rpm", maximumRpm)
    put("average_throttle", averageThrottlePercent)
    estimatedFuelLitres?.let { put("estimated_fuel_litres", it) }
    startArea?.let { put("start_area", it) }
    endArea?.let { put("end_area", it) }
    startLatitude?.let { put("start_latitude", it) }
    startLongitude?.let { put("start_longitude", it) }
    endLatitude?.let { put("end_latitude", it) }
    endLongitude?.let { put("end_longitude", it) }
    routePreview.takeIf { it.isNotEmpty() }?.let { put("route_preview", it.encode()) }
    zeroToSixtyMillis?.let { put("zero_to_sixty", it) }
    zeroToHundredMillis?.let { put("zero_to_hundred", it) }
    telemetryDurationMillis?.let { put("telemetry_duration", it) }
}

private fun Cursor.nullableDouble(index: Int): Double? {
    return if (isNull(index)) null else getDouble(index)
}

private fun SQLiteStatement.bindNullableLong(index: Int, value: Long?) {
    if (value == null) bindNull(index) else bindLong(index, value)
}

private fun Cursor.nullableLong(index: Int): Long? {
    return if (isNull(index)) null else getLong(index)
}

private fun Cursor.nullableString(index: Int): String? {
    return if (isNull(index)) null else getString(index)
}

// The route preview is a short, fixed-size list of coordinates stored as one text column
// rather than its own table. Coordinates contain no delimiter characters, so a
// semicolon-and-comma encoding is unambiguous, and it keeps the history query to one read.

internal fun List<RoutePoint>.encode(): String = joinToString(";") { "${it.latitude},${it.longitude}" }

/** Skips any malformed point rather than failing: a bad preview must not hide the ride. */
internal fun String?.decodeRoute(): List<RoutePoint> = this?.split(';').orEmpty().mapNotNull { encoded ->
    val values = encoded.split(',', limit = 2)
    val latitude = values.getOrNull(0)?.toDoubleOrNull() ?: return@mapNotNull null
    val longitude = values.getOrNull(1)?.toDoubleOrNull() ?: return@mapNotNull null
    RoutePoint(latitude, longitude).takeIf(RoutePoint::isValid)
}
