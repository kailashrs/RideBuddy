package com.spaceboy.ridebuddy.data

import com.spaceboy.ridebuddy.data.db.RideBuddyDatabase
import com.spaceboy.ridebuddy.data.db.RideSampleSeries
import com.spaceboy.ridebuddy.data.db.RawTelemetryDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

/**
 * Ride history: summaries in one database, each ride's samples as one blob in another.
 *
 * The split exists for backup, not for queries — see [RawTelemetryDatabase]. It costs atomicity
 * across the two files, so an insert that fails on the samples side removes the summary again.
 */
class RideRepository(
    private val history: RideBuddyDatabase,
    private val sampleStore: RawTelemetryDatabase,
    scope: CoroutineScope,
) {
    val rides: StateFlow<List<Ride>> =
        history.rides().observeAll().stateIn(scope, SharingStarted.Eagerly, emptyList())

    suspend fun ride(id: Long): Ride? = history.rides().find(id)

    /** Stores a ride with its samples and returns its new id. */
    suspend fun insert(ride: Ride, samples: List<RideSample> = emptyList()): Long {
        val id = history.rides().insert(ride.copy(id = 0))
        if (samples.isEmpty()) return id
        try {
            val data = withContext(Dispatchers.Default) { encodeSampleSeries(ride.startedAtMillis, samples) }
            sampleStore.samples().upsert(RideSampleSeries(id, ride.startedAtMillis, data))
        } catch (error: Exception) {
            history.rides().delete(id)
            throw error
        }
        return id
    }

    suspend fun updateAreas(rideId: Long, startArea: String?, endArea: String?) =
        history.rides().updateAreas(rideId, startArea, endArea)

    /** Empty for a ride whose samples have aged out under the retention setting. */
    suspend fun samples(rideId: Long): List<RideSample> {
        val ride = ride(rideId) ?: return emptyList()
        val data = sampleStore.samples().data(rideId) ?: return emptyList()
        return withContext(Dispatchers.Default) { decodeSampleSeries(ride.startedAtMillis, data) }
    }

    /** Drops the samples of rides started before [cutoffMillis]; returns how many rides lost them. */
    suspend fun pruneSamplesStartedBefore(cutoffMillis: Long): Int =
        sampleStore.samples().deleteStartedBefore(cutoffMillis)

    suspend fun delete(rideId: Long) {
        history.rides().delete(rideId)
        sampleStore.samples().delete(rideId)
    }

    suspend fun clear() {
        history.rides().deleteAll()
        sampleStore.samples().deleteAll()
    }
}

// The route preview is a short list of coordinates stored as one text column. Coordinates
// contain no delimiter characters, so a semicolon-and-comma encoding is unambiguous.

internal fun List<RoutePoint>.encode(): String = joinToString(";") { "${it.latitude},${it.longitude}" }

/** Skips any malformed point rather than failing: a bad preview must not hide the ride. */
internal fun String?.decodeRoute(): List<RoutePoint> = this?.split(';').orEmpty().mapNotNull { encoded ->
    val values = encoded.split(',', limit = 2)
    val latitude = values.getOrNull(0)?.toDoubleOrNull() ?: return@mapNotNull null
    val longitude = values.getOrNull(1)?.toDoubleOrNull() ?: return@mapNotNull null
    RoutePoint(latitude, longitude).takeIf(RoutePoint::isValid)
}
