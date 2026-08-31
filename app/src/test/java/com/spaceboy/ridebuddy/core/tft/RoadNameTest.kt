package com.spaceboy.ridebuddy.core.tft

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The cluster's instruction row is sixteen characters, so what goes in it has to be the road
 * rather than the whole sentence. These pin the fallback used when the navigation SDK gives no
 * road name of its own.
 */
class RoadNameTest {
    @Test
    fun `the maneuver phrase is dropped so the road survives the sixteen-character row`() {
        assertEquals("Garden Road", "Head east on Garden Road".roadNameOrSelf())
        assertEquals("Main St", "Turn right onto Main St".roadNameOrSelf())
        assertEquals("Highway 1", "Continue on Highway 1".roadNameOrSelf())
    }

    @Test
    fun `onto wins over on, so a road containing on is not cut short`() {
        assertEquals("Longon Street", "Turn left onto Longon Street".roadNameOrSelf())
    }

    @Test
    fun `a sentence with no road in it is left alone`() {
        assertEquals("Take the exit", "Take the exit".roadNameOrSelf())
        assertEquals("Arrive at your destination", "Arrive at your destination".roadNameOrSelf())
    }

    @Test
    fun `a trailing separator is not treated as a road name`() {
        assertEquals("Head east on ", "Head east on ".roadNameOrSelf())
    }
}
