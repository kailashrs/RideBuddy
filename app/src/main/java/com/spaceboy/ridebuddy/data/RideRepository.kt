package com.spaceboy.ridebuddy.data

import android.content.ContentValues
import android.content.Context
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

class RideRepository(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    databaseName: String = DefaultRideDatabaseName,
) {
    private val database = RideDatabase(context.applicationContext, databaseName)
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

private class RideDatabase(context: Context, name: String) : SQLiteOpenHelper(context, name, null, Version) {
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
                zero_to_hundred INTEGER
            )""".trimIndent(),
        )
        createSamplesTable(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        recreate(db)
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = recreate(db)

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
                ride.estimatedFuelLitres?.let { put("estimated_fuel_litres", it) }
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
            db.compileStatement(InsertSampleSql).use { statement ->
                samples.forEach { sample ->
                    statement.clearBindings()
                    statement.bindLong(1, rideId)
                    statement.bindLong(2, sample.timestampMillis)
                    statement.bindDouble(3, sample.speedKph)
                    statement.bindLong(4, sample.rpm)
                    statement.bindLong(5, sample.throttlePercent.toLong())
                    statement.bindNullableDouble(6, sample.mileageKilometresPerLitre)
                    statement.bindDouble(7, sample.accelerationMetresPerSecondSquared)
                    statement.bindNullableDouble(8, sample.latitude)
                    statement.bindNullableDouble(9, sample.longitude)
                    statement.bindNullableDouble(10, sample.accuracyMetres?.toDouble())
                    statement.bindNullableDouble(11, sample.altitudeMetres)
                    statement.executeInsert()
                }
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
        val timestamp = cursor.getColumnIndexOrThrow("timestamp")
        val speed = cursor.getColumnIndexOrThrow("speed")
        val rpm = cursor.getColumnIndexOrThrow("rpm")
        val throttle = cursor.getColumnIndexOrThrow("throttle")
        val mileage = cursor.getColumnIndexOrThrow("mileage_km_per_litre")
        val acceleration = cursor.getColumnIndexOrThrow("acceleration")
        val latitude = cursor.getColumnIndexOrThrow("latitude")
        val longitude = cursor.getColumnIndexOrThrow("longitude")
        val accuracy = cursor.getColumnIndexOrThrow("accuracy")
        val altitude = cursor.getColumnIndexOrThrow("altitude")
        buildList(cursor.count) {
            while (cursor.moveToNext()) add(
                RideSample(
                    timestampMillis = cursor.getLong(timestamp),
                    speedKph = cursor.getDouble(speed),
                    rpm = cursor.getLong(rpm),
                    throttlePercent = cursor.getInt(throttle),
                    mileageKilometresPerLitre = cursor.nullableDouble(mileage),
                    accelerationMetresPerSecondSquared = cursor.getDouble(acceleration),
                    latitude = cursor.nullableDouble(latitude),
                    longitude = cursor.nullableDouble(longitude),
                    accuracyMetres = cursor.nullableDouble(accuracy)?.toFloat(),
                    altitudeMetres = cursor.nullableDouble(altitude),
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
                mileage_km_per_litre REAL,
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
                    ),
                )
            }
        }
    }

    companion object {
        const val Table = "rides"
        const val SamplesTable = "ride_samples"
        const val Version = 4
        private const val InsertSampleSql = """INSERT INTO $SamplesTable (
            ride_id, timestamp, speed, rpm, throttle, mileage_km_per_litre,
            acceleration, latitude, longitude, accuracy, altitude
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"""
    }

    private fun recreate(db: SQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS $SamplesTable")
        db.execSQL("DROP TABLE IF EXISTS $Table")
        onCreate(db)
    }
}

private fun Cursor.nullableDouble(index: Int): Double? {
    return if (isNull(index)) null else getDouble(index)
}

private fun SQLiteStatement.bindNullableDouble(index: Int, value: Double?) {
    if (value == null) bindNull(index) else bindDouble(index, value)
}

private fun Cursor.nullableLong(index: Int): Long? {
    return if (isNull(index)) null else getLong(index)
}

private fun Cursor.nullableString(index: Int): String? {
    return if (isNull(index)) null else getString(index)
}

private fun List<RoutePoint>.encode(): String = joinToString(";") { "${it.latitude},${it.longitude}" }

private fun String?.decodeRoute(): List<RoutePoint> = this?.split(';').orEmpty().mapNotNull { encoded ->
    val values = encoded.split(',', limit = 2)
    val latitude = values.getOrNull(0)?.toDoubleOrNull() ?: return@mapNotNull null
    val longitude = values.getOrNull(1)?.toDoubleOrNull() ?: return@mapNotNull null
    RoutePoint(latitude, longitude)
}
