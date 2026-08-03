package com.spaceboy.ridebuddy.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.database.sqlite.transaction
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class RideRepository(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val database = RideDatabase(context.applicationContext)
    private val databaseMutex = Mutex()
    private val mutableRides = MutableStateFlow<List<Ride>>(emptyList())
    val rides: StateFlow<List<Ride>> = mutableRides.asStateFlow()

    suspend fun refresh() = withContext(ioDispatcher) {
        databaseMutex.withLock {
            mutableRides.value = database.readRides()
        }
    }

    suspend fun insert(ride: Ride, samples: List<RideSample> = emptyList()): Long = withContext(ioDispatcher) {
        databaseMutex.withLock {
            val rideId = database.insertRide(ride, samples)
            mutableRides.value = database.readRides()
            rideId
        }
    }

    suspend fun updateAreas(rideId: Long, startArea: String?, endArea: String?) = withContext(ioDispatcher) {
        databaseMutex.withLock {
            database.updateAreas(rideId, startArea, endArea)
            mutableRides.value = database.readRides()
        }
    }

    suspend fun samples(rideId: Long): List<RideSample> = withContext(ioDispatcher) {
        databaseMutex.withLock { database.readSamples(rideId) }
    }

    suspend fun clear() = withContext(ioDispatcher) {
        databaseMutex.withLock {
            database.writableDatabase.delete(RideDatabase.Table, null, null)
            mutableRides.value = emptyList()
        }
    }
}

private class RideDatabase(context: Context) : SQLiteOpenHelper(context, Name, null, Version) {
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
                average_consumption REAL NOT NULL,
                estimated_fuel REAL NOT NULL,
                start_area TEXT,
                end_area TEXT,
                start_latitude REAL,
                start_longitude REAL,
                end_latitude REAL,
                end_longitude REAL,
                route_preview TEXT,
                zero_to_sixty INTEGER,
                zero_to_hundred INTEGER
            )""".trimIndent(),
        )
        createSamplesTable(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createSamplesTable(db)
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE $Table ADD COLUMN start_area TEXT")
            db.execSQL("ALTER TABLE $Table ADD COLUMN end_area TEXT")
            db.execSQL("ALTER TABLE $Table ADD COLUMN start_latitude REAL")
            db.execSQL("ALTER TABLE $Table ADD COLUMN start_longitude REAL")
            db.execSQL("ALTER TABLE $Table ADD COLUMN end_latitude REAL")
            db.execSQL("ALTER TABLE $Table ADD COLUMN end_longitude REAL")
            db.execSQL("ALTER TABLE $Table ADD COLUMN route_preview TEXT")
            db.execSQL("ALTER TABLE $Table ADD COLUMN zero_to_sixty INTEGER")
            db.execSQL("ALTER TABLE $Table ADD COLUMN zero_to_hundred INTEGER")
        }
    }

    fun insertRide(ride: Ride, samples: List<RideSample>): Long {
        val db = writableDatabase
        return db.transaction {
            val rideId = db.insertOrThrow(Table, null, ContentValues().apply {
                put("started_at", ride.startedAtMillis)
                put("ended_at", ride.endedAtMillis)
                put("distance_km", ride.distanceKilometres)
                put("average_speed", ride.averageSpeedKph)
                put("maximum_speed", ride.maximumSpeedKph)
                put("average_rpm", ride.averageRpm)
                put("maximum_rpm", ride.maximumRpm)
                put("average_throttle", ride.averageThrottlePercent)
                put("average_consumption", ride.averageConsumptionLPer100Km)
                put("estimated_fuel", ride.estimatedFuelLitres)
                ride.startArea?.let { put("start_area", it) }
                ride.endArea?.let { put("end_area", it) }
                ride.startLatitude?.let { put("start_latitude", it) }
                ride.startLongitude?.let { put("start_longitude", it) }
                ride.endLatitude?.let { put("end_latitude", it) }
                ride.endLongitude?.let { put("end_longitude", it) }
                ride.routePreview.takeIf { it.isNotEmpty() }?.let { put("route_preview", it.encode()) }
                ride.zeroToSixtyMillis?.let { put("zero_to_sixty", it) }
                ride.zeroToHundredMillis?.let { put("zero_to_hundred", it) }
            })
            samples.forEach { sample ->
                db.insertOrThrow(SamplesTable, null, ContentValues().apply {
                    put("ride_id", rideId)
                    put("timestamp", sample.timestampMillis)
                    put("speed", sample.speedKph)
                    put("rpm", sample.rpm)
                    put("throttle", sample.throttlePercent)
                    put("consumption", sample.consumptionLPer100Km)
                    put("acceleration", sample.accelerationMetresPerSecondSquared)
                    sample.latitude?.let { put("latitude", it) }
                    sample.longitude?.let { put("longitude", it) }
                    sample.accuracyMetres?.let { put("accuracy", it) }
                    sample.altitudeMetres?.let { put("altitude", it) }
                })
            }
            rideId
        }
    }

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

    fun readSamples(rideId: Long): List<RideSample> = readableDatabase.query(
        SamplesTable, null, "ride_id = ?", arrayOf(rideId.toString()), null, null, "timestamp ASC",
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(
                RideSample(
                    timestampMillis = cursor.getLong(cursor.getColumnIndexOrThrow("timestamp")),
                    speedKph = cursor.getDouble(cursor.getColumnIndexOrThrow("speed")),
                    rpm = cursor.getLong(cursor.getColumnIndexOrThrow("rpm")),
                    throttlePercent = cursor.getInt(cursor.getColumnIndexOrThrow("throttle")),
                    consumptionLPer100Km = cursor.getDouble(cursor.getColumnIndexOrThrow("consumption")),
                    accelerationMetresPerSecondSquared = cursor.getDouble(cursor.getColumnIndexOrThrow("acceleration")),
                    latitude = cursor.nullableDouble("latitude"),
                    longitude = cursor.nullableDouble("longitude"),
                    accuracyMetres = cursor.nullableDouble("accuracy")?.toFloat(),
                    altitudeMetres = cursor.nullableDouble("altitude"),
                ),
            )
        }
    }

    private fun createSamplesTable(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS $SamplesTable (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ride_id INTEGER NOT NULL REFERENCES $Table(id) ON DELETE CASCADE,
                timestamp INTEGER NOT NULL,
                speed REAL NOT NULL,
                rpm INTEGER NOT NULL,
                throttle INTEGER NOT NULL,
                consumption REAL NOT NULL,
                acceleration REAL NOT NULL,
                latitude REAL,
                longitude REAL,
                accuracy REAL,
                altitude REAL
            )""".trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS samples_ride_time ON $SamplesTable(ride_id, timestamp)")
    }

    fun readRides(): List<Ride> = readableDatabase.query(
        Table,
        null,
        null,
        null,
        null,
        null,
        "started_at DESC",
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(
                    Ride(
                        id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                        startedAtMillis = cursor.getLong(cursor.getColumnIndexOrThrow("started_at")),
                        endedAtMillis = cursor.getLong(cursor.getColumnIndexOrThrow("ended_at")),
                        distanceKilometres = cursor.getDouble(cursor.getColumnIndexOrThrow("distance_km")),
                        averageSpeedKph = cursor.getDouble(cursor.getColumnIndexOrThrow("average_speed")),
                        maximumSpeedKph = cursor.getDouble(cursor.getColumnIndexOrThrow("maximum_speed")),
                        averageRpm = cursor.getDouble(cursor.getColumnIndexOrThrow("average_rpm")),
                        maximumRpm = cursor.getLong(cursor.getColumnIndexOrThrow("maximum_rpm")),
                        averageThrottlePercent = cursor.getDouble(cursor.getColumnIndexOrThrow("average_throttle")),
                        averageConsumptionLPer100Km = cursor.getDouble(cursor.getColumnIndexOrThrow("average_consumption")),
                        estimatedFuelLitres = cursor.getDouble(cursor.getColumnIndexOrThrow("estimated_fuel")),
                        startArea = cursor.nullableString("start_area"),
                        endArea = cursor.nullableString("end_area"),
                        startLatitude = cursor.nullableDouble("start_latitude"),
                        startLongitude = cursor.nullableDouble("start_longitude"),
                        endLatitude = cursor.nullableDouble("end_latitude"),
                        endLongitude = cursor.nullableDouble("end_longitude"),
                        routePreview = cursor.nullableString("route_preview").decodeRoute(),
                        zeroToSixtyMillis = cursor.nullableLong("zero_to_sixty"),
                        zeroToHundredMillis = cursor.nullableLong("zero_to_hundred"),
                    ),
                )
            }
        }
    }

    companion object {
        const val Table = "rides"
        const val SamplesTable = "ride_samples"
        const val Name = "rides.db"
        const val Version = 3
    }
}

private fun android.database.Cursor.nullableDouble(column: String): Double? {
    val index = getColumnIndexOrThrow(column)
    return if (isNull(index)) null else getDouble(index)
}

private fun android.database.Cursor.nullableLong(column: String): Long? {
    val index = getColumnIndexOrThrow(column)
    return if (isNull(index)) null else getLong(index)
}

private fun android.database.Cursor.nullableString(column: String): String? {
    val index = getColumnIndexOrThrow(column)
    return if (isNull(index)) null else getString(index)
}

private fun List<RoutePoint>.encode(): String = joinToString(";") { "${it.latitude},${it.longitude}" }

private fun String?.decodeRoute(): List<RoutePoint> = this?.split(';').orEmpty().mapNotNull { encoded ->
    val values = encoded.split(',', limit = 2)
    val latitude = values.getOrNull(0)?.toDoubleOrNull() ?: return@mapNotNull null
    val longitude = values.getOrNull(1)?.toDoubleOrNull() ?: return@mapNotNull null
    RoutePoint(latitude, longitude)
}
