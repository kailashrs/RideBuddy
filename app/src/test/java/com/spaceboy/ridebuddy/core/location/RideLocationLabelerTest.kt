package com.spaceboy.ridebuddy.core.location

import android.location.Address
import android.location.Geocoder
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RideLocationLabelerTest {
    @Test fun streetOnlyAddressIsUsedBeforeCoordinates() {
        val address = Address(Locale.US).apply { thoroughfare = "Beach Road" }
        assertEquals("Beach Road", address.shortPlaceLabel())
        address.thoroughfare = null
        address.setAddressLine(0, "Marina Beach, Chennai")
        assertEquals("Marina Beach, Chennai", address.shortPlaceLabel())
    }

    @Test fun areaNamesAreShortAndDeduplicated() {
        val address = Address(Locale.US).apply {
            subLocality = "Mylapore"; locality = "Chennai"; adminArea = "Tamil Nadu"
        }
        assertEquals("Mylapore, Chennai", address.shortPlaceLabel())
        address.subLocality = "Chennai"
        assertEquals("Chennai, Tamil Nadu", address.shortPlaceLabel())
    }

    @Test fun onlyMissingAndCoordinateLabelsNeedAnotherLookup() {
        assertTrue(needsPlaceName(null))
        assertTrue(needsPlaceName("13.0500, 80.2800"))
        assertTrue(needsPlaceName("-13.0500, -80.2800"))
        assertFalse(needsPlaceName("Marina Beach"))
    }

    @Test fun failedLookupCanResolveOnALaterVisit() = runBlocking {
        val geocoder = Geocoder(ApplicationProvider.getApplicationContext(), Locale.US)
        val shadow = shadowOf(geocoder)
        val labeler = RideLocationLabeler(geocoder)
        shadow.setFromLocation(emptyList())
        val fallback = labeler.label(13.05, 80.28)
        assertEquals("13.0500, 80.2800", fallback)
        assertNull(labeler.placeName(13.05, 80.28))
        shadow.setFromLocation(listOf(Address(Locale.US).apply { locality = "Chennai" }))
        assertTrue(needsPlaceName(fallback))
        assertEquals("Chennai", labeler.label(13.05, 80.28))
    }
}
