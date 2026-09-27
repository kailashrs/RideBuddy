package com.spaceboy.ridebuddy

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NavigationStopCopyTest {
    @Test
    fun failedRouteStopUsesBriefActionableCopy() {
        val message = ApplicationProvider.getApplicationContext<Context>()
            .getString(R.string.navigation_end_request_failed)

        assertEquals("Couldn’t stop navigation. Try again.", message)
        assertFalse(message.contains("Google"))
        assertFalse(message.contains("SDK"))
    }
}
