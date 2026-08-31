package com.spaceboy.ridebuddy

import android.app.Application
import com.google.android.gms.maps.MapsInitializer

/**
 * Process entry point. Owns the single [AppContainer] every component reaches through
 * [appContainer], and performs the one-time SDK initialisation that must not race.
 */
class RideBuddyApplication : Application() {
    val container: AppContainer by lazy { AppContainer(applicationContext) }

    override fun onCreate() {
        super.onCreate()
        // Force construction here rather than on first use. Several entry points are
        // system-driven — the notification listener, the presence service — and the first
        // touch could otherwise happen on a callback thread mid-work.
        container
        // Initialise the Maps SDK renderer once for the whole process so the
        // first CameraUpdateFactory call from any Composable cannot race the
        // internal delegate binding. Initialisation is idempotent.
        MapsInitializer.initialize(applicationContext, MapsInitializer.Renderer.LATEST, null)
    }
}
