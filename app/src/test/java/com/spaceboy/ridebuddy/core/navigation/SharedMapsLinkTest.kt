package com.spaceboy.ridebuddy.core.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The link shapes Google Maps actually produces when a rider shares somewhere.
 *
 * A saved parking spot ("you parked here") and a dropped pin are the two that used to fail:
 * both share as a place whose coordinates live in the `data` blob or in a degrees/minutes/
 * seconds path, neither of which the parser read, so both fell through to the geocoder with
 * the whole URL as the query and came back "could not find that destination".
 */
class SharedMapsLinkTest {
    @Test
    fun savedParkingSpotResolvesFromThePlaceCoordinatesInTheDataBlob() {
        val destination = directNavigationDestination(
            "https://www.google.com/maps/place/Parking+location/data=!4m6!3m5!1s0x3bae1670c9b44e6d:0x1" +
                "!8m2!3d12.9789167!4d77.5947222!16s%2Fg%2F11abc?entry=ttu",
        )

        assertNotNull(destination)
        assertEquals(12.9789167, destination!!.latitude, 1e-7)
        assertEquals(77.5947222, destination.longitude, 1e-7)
    }

    @Test
    fun aDroppedPinResolvesFromItsDegreesMinutesSecondsPath() {
        // Percent-encoded exactly as Maps writes it: %C2%B0 is the degree sign, %22 the quote.
        val destination = directNavigationDestination(
            "https://www.google.com/maps/place/12%C2%B058'44.1%22N+77%C2%B035'41.0%22E/data=!3m1!1e3",
        )

        assertNotNull(destination)
        assertEquals(12.97891, destination!!.latitude, 1e-4)
        assertEquals(77.59472, destination.longitude, 1e-4)
    }

    @Test
    fun southernAndWesternHemispheresKeepTheirSign() {
        val destination = directNavigationDestination(
            "https://www.google.com/maps/place/33%C2%B052'07.0%22S+151%C2%B012'34.0%22W/",
        )

        assertNotNull(destination)
        assertEquals(-33.86861, destination!!.latitude, 1e-4)
        assertEquals(-151.20944, destination.longitude, 1e-4)
    }

    @Test
    fun thePlaceCoordinateWinsOverTheMapViewport() {
        // `@` is only where the map was centred; !3d/!4d is the place itself. A pin near the
        // edge of the viewport would otherwise navigate to the middle of the screen instead.
        val destination = directNavigationDestination(
            "https://www.google.com/maps/place/Somewhere/@12.9000000,77.5000000,15z/data=!4m2!3m1!8m2!3d12.9789167!4d77.5947222",
        )

        assertEquals(12.9789167, destination!!.latitude, 1e-7)
        assertEquals(77.5947222, destination.longitude, 1e-7)
    }

    @Test
    fun theOlderPinFormWithOnlyAViewportStillResolves() {
        val destination = directNavigationDestination("https://www.google.com/maps/@12.9789167,77.5947222,17z")

        assertEquals(12.9789167, destination!!.latitude, 1e-7)
    }

    @Test
    fun aCoordinateBehindLocPrefixResolves() {
        val destination = directNavigationDestination("https://maps.google.com/?q=loc:12.9789167,77.5947222")

        assertEquals(12.9789167, destination!!.latitude, 1e-7)
        assertEquals(77.5947222, destination.longitude, 1e-7)
    }

    @Test
    fun aCentreParameterResolves() {
        val destination = directNavigationDestination("https://maps.google.com/maps?ll=12.9789167,77.5947222&z=17")

        assertEquals(12.9789167, destination!!.latitude, 1e-7)
    }

    @Test
    fun nonsenseDegreesAreRejectedRatherThanNavigatedTo() {
        assertNull(directNavigationDestination("https://www.google.com/maps/place/12%C2%B099'44.1%22N+77%C2%B035'41.0%22E/"))
    }

    @Test
    fun aNamedPlaceWithNoCoordinatesGeocodesItsNameNotItsUrl() {
        // The whole point: the geocoder gets "Cubbon Park", which resolves, instead of the
        // URL, which never could.
        assertEquals(
            "Cubbon Park",
            geocodableText("https://www.google.com/maps/place/Cubbon+Park/data=!4m2!3m1!1s0x3bae1670c9b44e6d:0x1"),
        )
    }

    @Test
    fun theViewportSegmentIsNotMistakenForAPlaceName() {
        assertEquals(
            "Cubbon Park",
            geocodableText("https://www.google.com/maps/place/Cubbon+Park/@12.9763,77.5929,17z/data=!3m1!4b1"),
        )
    }

    @Test
    fun aQueryParameterBeatsThePath() {
        assertEquals(
            "Cubbon Park, Bengaluru",
            geocodableText("https://www.google.com/maps/search/?api=1&query=Cubbon%20Park%2C%20Bengaluru"),
        )
    }

    @Test
    fun aLinkNamingNoPlaceAtAllIsReportedRatherThanGeocoded() {
        // Only an internal feature id. Sending this to the geocoder is what produced the
        // misleading "could not find that destination".
        assertNull(geocodableText("https://www.google.com/maps/place//data=!4m2!3m1!1s0x3bae1670c9b44e6d:0x1"))
    }

    @Test
    fun aSharedRouteResolvesToItsDestinationRatherThanItsOrigin() {
        // The shape Google produces for a shared route: two coordinate stops in the path, no
        // "@" and no query parameter naming either of them. Taking the first stop would
        // navigate the rider to where they set off from, which looks like success.
        val destination = directNavigationDestination(
            "https://www.google.com/maps/dir/12.9292616,77.1932329/12.9549843,77.1704513/" +
                "data=!4m8!4m7!1m1!4e1!1m1!4e1!2m1!11b1!3e9?utm_source=mstt_0&g_ep=CAESBzI2",
        )

        assertNotNull(destination)
        assertEquals(12.9549843, destination!!.latitude, 1e-7)
        assertEquals(77.1704513, destination.longitude, 1e-7)
    }

    @Test
    fun aRouteFromTheRidersCurrentPositionStillResolves() {
        // "Directions from here" leaves the origin blank.
        val destination = directNavigationDestination(
            "https://www.google.com/maps/dir//12.9549843,77.1704513/data=!4m2!4m1!3e0",
        )

        assertEquals(12.9549843, destination!!.latitude, 1e-7)
    }

    @Test
    fun aRouteToANamedPlaceGeocodesTheLastStop() {
        assertEquals(
            "Cubbon Park",
            geocodableText("https://www.google.com/maps/dir/Indiranagar/Cubbon+Park/data=!4m2!4m1!3e0"),
        )
    }

    @Test
    fun aMultiStopRouteTakesTheFinalStop() {
        val destination = directNavigationDestination(
            "https://www.google.com/maps/dir/12.90,77.10/12.92,77.12/12.95,77.17/data=!4m2!4m1!3e0",
        )

        assertEquals(12.95, destination!!.latitude, 1e-7)
        assertEquals(77.17, destination.longitude, 1e-7)
    }

    @Test
    fun aTypedAddressIsPassedThroughUntouched() {
        assertEquals("100 Feet Road, Indiranagar", geocodableText("  100 Feet Road, Indiranagar  "))
    }
}
