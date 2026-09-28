package com.spaceboy.ridebuddy

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.android.gms.maps.MapsInitializer
import com.google.android.gms.maps.OnMapsSdkInitializedCallback
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Resetter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = RideBuddyApplication::class, shadows = [CountingMapsInitializer::class])
class MapInitializationTest {
    @Test fun applicationStartupLeavesMapsUntouched() {
        ApplicationProvider.getApplicationContext<RideBuddyApplication>()
        assertEquals(0, CountingMapsInitializer.calls)
    }
}

@Implements(MapsInitializer::class)
class CountingMapsInitializer {
    companion object {
        var calls = 0
        @JvmStatic @Resetter fun reset() { calls = 0 }
        @JvmStatic @Implementation fun initialize(context: Context): Int { calls++; return 0 }
        @JvmStatic @Implementation fun initialize(
            context: Context, renderer: MapsInitializer.Renderer, callback: OnMapsSdkInitializedCallback?,
        ): Int { calls++; return 0 }
    }
}
