package com.spaceboy.ridebuddy.data

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.room.withTransaction
import com.spaceboy.ridebuddy.data.db.RideHistoryDatabase
import com.spaceboy.ridebuddy.data.db.RideSampleSeries
import com.spaceboy.ridebuddy.data.db.RideSamplesDatabase
import java.io.File

/**
 * One-time import of the pre-1.1 `rides.db` (schema version 6) into the Room databases.
 *
 * Only version 6 is read; anything else is left alone. The legacy file is renamed rather than
 * deleted once the import is verified, and a failed or mismatched import empties the new
 * databases again, which is safe because it only runs while they are empty.
 */
internal class LegacyRideImporter(
    private val legacyDatabase: File,
    private val legacyBackupSnapshot: File,
    private val history: RideHistoryDatabase,
    private val sampleStore: RideSamplesDatabase,
) {
    /** Returns the number of rides imported. */
    suspend fun importIfPresent(): Int {
        legacyBackupSnapshot.delete()
        if (!legacyDatabase.isFile) return 0
        if (history.rides().count() > 0) {
            Log.w(LogTag, "Ride history already populated; leaving ${legacyDatabase.name} untouched")
            return 0
        }
        val legacy = SQLiteDatabase.openDatabase(legacyDatabase.path, null, SQLiteDatabase.OPEN_READONLY)
        try {
            if (legacy.version != SupportedVersion) {
                Log.w(LogTag, "Legacy ride database is version ${legacy.version}; only $SupportedVersion is imported")
                return 0
            }
            val rides = legacy.readRides()
            val series = legacy.readSampleSeries(rides.associate { it.id to it.startedAtMillis })
            try {
                history.withTransaction { history.rides().insertAll(rides) }
                sampleStore.withTransaction {
                    sampleStore.samples().upsertAll(series.map { (rideId, entry) -> entry.series(rideId) })
                }
                verify(rides.size, series.size)
            } catch (error: Exception) {
                history.rides().deleteAll()
                sampleStore.samples().deleteAll()
                throw error
            }
        } finally {
            legacy.close()
        }
        retireLegacyFiles()
        Log.i(LogTag, "Imported ride history from ${legacyDatabase.name}")
        return history.rides().count()
    }

    private suspend fun verify(expectedRides: Int, expectedSeries: Int) {
        val rides = history.rides().count()
        val series = sampleStore.samples().count()
        check(rides == expectedRides && series == expectedSeries) {
            "Imported $rides/$expectedRides rides and $series/$expectedSeries sample series"
        }
    }

    private fun retireLegacyFiles() {
        listOf("", "-journal", "-wal", "-shm").forEach { suffix ->
            val file = File(legacyDatabase.path + suffix)
            if (file.exists()) file.renameTo(File(legacyDatabase.path + suffix + RetiredSuffix))
        }
    }

    private class LegacySeries(val startedAtMillis: Long, val samples: List<RideSample>) {
        fun series(rideId: Long) = RideSampleSeries(rideId, startedAtMillis, encodeSampleSeries(startedAtMillis, samples))
    }

    private fun SQLiteDatabase.readRides(): List<Ride> =
        rawQuery("SELECT * FROM rides ORDER BY id", null).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        Ride(
                            id = cursor.long("id"),
                            startedAtMillis = cursor.long("started_at"),
                            endedAtMillis = cursor.long("ended_at"),
                            distanceKilometres = cursor.double("distance_km"),
                            averageSpeedKph = cursor.double("average_speed"),
                            maximumSpeedKph = cursor.double("maximum_speed"),
                            averageRpm = cursor.double("average_rpm"),
                            maximumRpm = cursor.long("maximum_rpm"),
                            averageThrottlePercent = cursor.double("average_throttle"),
                            estimatedFuelLitres = cursor.nullableDouble("estimated_fuel_litres"),
                            startArea = cursor.nullableString("start_area"),
                            endArea = cursor.nullableString("end_area"),
                            startLatitude = cursor.nullableDouble("start_latitude"),
                            startLongitude = cursor.nullableDouble("start_longitude"),
                            endLatitude = cursor.nullableDouble("end_latitude"),
                            endLongitude = cursor.nullableDouble("end_longitude"),
                            routePreview = cursor.nullableString("route_preview").decodeRoute(),
                            zeroToSixtyMillis = cursor.nullableLong("zero_to_sixty"),
                            zeroToHundredMillis = cursor.nullableLong("zero_to_hundred"),
                            telemetryDurationMillis = cursor.nullableLong("telemetry_duration"),
                        ),
                    )
                }
            }
        }

    /**
     * Version 6 stored each sample as fixed-point integers with its time as an offset from the
     * ride's start. Samples whose ride no longer exists are dropped, as the old join did.
     */
    private fun SQLiteDatabase.readSampleSeries(startTimes: Map<Long, Long>): Map<Long, LegacySeries> =
        rawQuery("SELECT * FROM ride_samples ORDER BY ride_id, t", null).use { cursor ->
            val samplesByRide = linkedMapOf<Long, MutableList<RideSample>>()
            while (cursor.moveToNext()) {
                val rideId = cursor.long("ride_id")
                val startedAt = startTimes[rideId] ?: continue
                samplesByRide.getOrPut(rideId) { mutableListOf() } += RideSample(
                    timestampMillis = startedAt + cursor.long("t"),
                    speedKph = cursor.long("speed") / SpeedScale,
                    rpm = cursor.long("rpm"),
                    throttlePercent = cursor.long("throttle").toInt(),
                    mileageKilometresPerLitre = cursor.nullableLong("mileage")?.div(MileageScale),
                    accelerationMetresPerSecondSquared = cursor.long("acceleration") / AccelerationScale,
                    latitude = cursor.nullableLong("latitude")?.div(CoordinateScale),
                    longitude = cursor.nullableLong("longitude")?.div(CoordinateScale),
                    accuracyMetres = cursor.nullableLong("accuracy")?.div(MetresScale)?.toFloat(),
                    altitudeMetres = cursor.nullableLong("altitude")?.div(MetresScale),
                )
            }
            samplesByRide.mapValues { (rideId, samples) -> LegacySeries(startTimes.getValue(rideId), samples) }
        }

    private fun Cursor.long(column: String): Long = getLong(getColumnIndexOrThrow(column))
    private fun Cursor.double(column: String): Double = getDouble(getColumnIndexOrThrow(column))
    private fun Cursor.nullableLong(column: String): Long? =
        getColumnIndexOrThrow(column).let { if (isNull(it)) null else getLong(it) }
    private fun Cursor.nullableDouble(column: String): Double? =
        getColumnIndexOrThrow(column).let { if (isNull(it)) null else getDouble(it) }
    private fun Cursor.nullableString(column: String): String? =
        getColumnIndexOrThrow(column).let { if (isNull(it)) null else getString(it) }

    companion object {
        const val SupportedVersion = 6
        const val RetiredSuffix = ".migrated"
        private const val LogTag = "LegacyRideImporter"

        // The version 6 fixed-point scales.
        private const val SpeedScale = 10.0
        private const val MileageScale = 10.0
        private const val AccelerationScale = 100.0
        private const val CoordinateScale = 10_000_000.0
        private const val MetresScale = 10.0
    }
}
