package com.spaceboy.ridebuddy.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class RideRouteTest {
    private fun ride(id: Long, preview: List<RoutePoint> = emptyList()) =
        Ride(id, id * 1_000, id * 1_000 + 500, 10.0, 30.0, 60.0, 4_000.0, 7_000, 25.0, null, routePreview = preview)

    private fun located(count: Int, latitude: Double) = List(count) { index ->
        RideSample(index.toLong(), 30.0, 4_000, 20, null, 0.0, latitude = latitude, longitude = 80.0 + index * 0.001)
    }

    @Test fun eachLocatedRideIsItsOwnLineAndAnUnlocatedOneIsLeftOut() = runBlocking {
        val samples = mapOf(1L to located(5, 12.0), 2L to List(5) { located(1, 0.0).single().copy(latitude = null) })
        val pruned = ride(3, preview = listOf(RoutePoint(13.0, 80.0), RoutePoint(13.1, 80.1)))

        val routes = tripRoutes(listOf(ride(1), ride(2), pruned)) { samples[it].orEmpty() }

        assertEquals(2, routes.size)
        assertEquals(5, routes[0].size)
        // Samples aged out under retention: the stored preview still gives the ride a line.
        assertEquals(listOf(13.0 to 80.0, 13.1 to 80.1), routes[1])
    }

    @Test fun aLongTripSharesThePointBudgetButKeepsEnoughForEachRide() = runBlocking {
        val rides = (1L..4L).map { ride(it) }
        val routes = tripRoutes(rides) { located(5_000, it.toDouble()) }
        routes.forEach { assertEquals(MaxRoutePoints / 4, it.size) }

        val many = (1L..40L).map { ride(it) }
        tripRoutes(many) { located(5_000, it.toDouble()) }.forEach { assertEquals(100, it.size) }
    }
}
