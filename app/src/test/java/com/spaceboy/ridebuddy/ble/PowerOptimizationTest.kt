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
        // Static reference: the type annotation enforces that the helper remains a
        // top-level extension function on Context. The reference itself is non-null by
        // construction, so no runtime assertion is needed.
        val signature: Context.() -> Boolean = ::isRideBuddyIgnoringBatteryOptimizations
    }
}