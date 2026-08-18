package com.spaceboy.ridebuddy.ble

import android.content.Context
import org.junit.Test

/**
 * Documents the contract of [isRideBuddyIgnoringBatteryOptimizations]. Runtime verification
 * requires Robolectric (or an instrumented test) because the helper reads `PowerManager`
 * from the system service registry, which is unavailable in a plain JUnit environment.
 */
class PowerOptimizationTest {
    @Test
    fun helperIsAnExtensionOnContext() {
        // Static reference: ensures the helper remains a top-level extension function on Context.
        val signature: Context.() -> Boolean = ::isRideBuddyIgnoringBatteryOptimizations
        assert(signature != null) {
            "isRideBuddyIgnoringBatteryOptimizations must remain a Context extension"
        }
    }
}