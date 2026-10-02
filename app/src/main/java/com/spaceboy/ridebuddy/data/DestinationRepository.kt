package com.spaceboy.ridebuddy.data

import com.spaceboy.ridebuddy.core.location.needsPlaceName
import com.spaceboy.ridebuddy.core.navigation.NavigationDestination
import com.spaceboy.ridebuddy.data.db.Destination
import com.spaceboy.ridebuddy.data.db.DestinationDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Recent and saved destinations.
 *
 * A trip is counted when guidance starts, not when a route is previewed, so a preview the rider
 * backs out of does not make a place look frequent. Shares of the same place rarely carry
 * identical coordinates, so anything within [SamePlaceMetres] of a known place is that place.
 */
class DestinationRepository(
    private val dao: DestinationDao,
    scope: CoroutineScope,
    /** The first line of the address at a point, for a destination that arrived without a name. */
    private val addressFirstLine: suspend (latitude: Double, longitude: Double) -> String?,
    private val now: () -> Long = System::currentTimeMillis,
) {
    val destinations: StateFlow<List<Destination>> =
        dao.observeAll().stateIn(scope, SharingStarted.Eagerly, emptyList())

    suspend fun recordTrip(destination: NavigationDestination) {
        val known = dao.all().nearest(destination.latitude, destination.longitude)
        if (known != null) {
            // A place first recorded without a name gets another lookup each time it is visited.
            val name = if (isUnnamed(known.placeName)) placeName(destination) else known.placeName
            dao.update(known.copy(placeName = name, tripCount = known.tripCount + 1, lastTripAtMillis = now()))
        } else {
            dao.insert(Destination(
                latitude = destination.latitude,
                longitude = destination.longitude,
                placeName = placeName(destination),
                tripCount = 1,
                lastTripAtMillis = now(),
            ))
        }
        prunable(dao.all()).takeIf { it.isNotEmpty() }?.let { dao.delete(it.map(Destination::id)) }
    }

    /** Saves [destination] under [name], keeping any trips already counted to it. */
    suspend fun save(destination: NavigationDestination, name: String) {
        val known = dao.all().nearest(destination.latitude, destination.longitude)
        if (known != null) {
            dao.rename(known.id, name.trim())
        } else {
            dao.insert(Destination(
                latitude = destination.latitude,
                longitude = destination.longitude,
                placeName = placeName(destination),
                savedName = name.trim(),
            ))
        }
    }

    suspend fun rename(id: Long, name: String) = dao.rename(id, name.trim())

    suspend fun delete(id: Long) = dao.delete(id)

    /** Clears trip history. Saved places stay, without their counts. */
    suspend fun clearRecents() {
        dao.deleteUnsaved()
        dao.resetTrips()
    }

    /**
     * A share whose address lookup timed out while the rider waited arrives as [GenericTitle], and
     * some shares name a place only by its coordinates. By the time guidance starts the lookup
     * has more time, so either is looked up once more.
     */
    private suspend fun placeName(destination: NavigationDestination): String =
        destination.title.takeUnless(::isUnnamed)
            ?: addressFirstLine(destination.latitude, destination.longitude)
            ?: destination.title

    private fun isUnnamed(title: String) = title == GenericTitle || needsPlaceName(title)

    internal companion object {
        const val RecentCount = 3
        const val SamePlaceMetres = 50.0
        const val MaxRecents = 20
        /** What a destination is called when nothing better was found for it. */
        const val GenericTitle = "Destination"
    }
}

/** Unsaved places with trips, most frequent first and the most recent winning a tie. */
internal fun List<Destination>.recents(limit: Int = DestinationRepository.RecentCount): List<Destination> =
    filter { !it.isSaved && it.tripCount > 0 }
        .sortedWith(compareByDescending<Destination> { it.tripCount }.thenByDescending { it.lastTripAtMillis ?: 0L })
        .take(limit)

/** Saved places, the most used first, then by name. */
internal fun List<Destination>.saved(): List<Destination> =
    filter(Destination::isSaved)
        .sortedWith(compareByDescending<Destination> { it.tripCount }.thenBy { it.savedName?.lowercase() })

/** The saved place at a point, if there is one. */
internal fun List<Destination>.savedAt(latitude: Double, longitude: Double): Destination? =
    filter(Destination::isSaved).nearest(latitude, longitude)

/** Unsaved places beyond the [DestinationRepository.MaxRecents] most relevant. */
internal fun prunable(all: List<Destination>): List<Destination> =
    all.recents(Int.MAX_VALUE).drop(DestinationRepository.MaxRecents)

internal fun List<Destination>.nearest(latitude: Double, longitude: Double): Destination? =
    map { it to distanceMetres(latitude, longitude, it.latitude, it.longitude) }
        .filter { (_, metres) -> metres <= DestinationRepository.SamePlaceMetres }
        .minByOrNull { (_, metres) -> metres }
        ?.first

/** Great-circle distance; plenty precise at the tens of metres this compares. */
internal fun distanceMetres(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
    return 2 * EarthRadiusMetres * asin(sqrt(a))
}

private const val EarthRadiusMetres = 6_371_000.0
