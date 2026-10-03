package com.spaceboy.ridebuddy.data

import com.spaceboy.ridebuddy.data.db.Trip
import com.spaceboy.ridebuddy.data.db.TripDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** A trip with the ids of the rides in it. */
data class TripSummary(val trip: Trip, val rideIds: Set<Long>)

/** The rides of [this] trip that still exist, oldest first. */
fun TripSummary.rides(all: List<Ride>): List<Ride> =
    all.filter { it.id in rideIds }.sortedBy(Ride::startedAtMillis)

/** Trips, the most recently ridden first; one with no rides yet sorts by when it was made. */
fun List<TripSummary>.sortedByLatestRide(rides: List<Ride>): List<TripSummary> {
    val startedAt = rides.associate { it.id to it.startedAtMillis }
    return sortedByDescending { summary -> summary.rideIds.maxOfOrNull { startedAt[it] ?: Long.MIN_VALUE } ?: summary.trip.createdAtMillis }
}

class TripRepository(
    private val dao: TripDao,
    scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) {
    val trips: StateFlow<List<TripSummary>> = combine(dao.observeTrips(), dao.observeTripRides()) { trips, links ->
        val rideIds = links.groupBy({ it.tripId }, { it.rideId })
        trips.map { TripSummary(it, rideIds[it.id].orEmpty().toSet()) }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Creates a trip when [id] is null, otherwise renames it and replaces its rides. */
    suspend fun save(id: Long?, name: String, rideIds: Set<Long>): Long = dao.save(id, name.trim(), rideIds, now())

    suspend fun delete(id: Long) = dao.delete(id)
}
