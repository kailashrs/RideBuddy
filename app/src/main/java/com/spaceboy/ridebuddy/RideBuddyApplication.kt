package com.spaceboy.ridebuddy

import android.app.Application
import com.google.android.gms.maps.MapsInitializer

class RideBuddyApplication : Application() {
    val container: AppContainer by lazy { AppContainer(applicationContext) }

    override fun onCreate() {
        super.onCreate()
        container
        // Initialise the Maps SDK renderer once for the whole process so the
        // first CameraUpdateFactory call from any Composable cannot race the
        // internal delegate binding. Initialisation is idempotent.
        MapsInitializer.initialize(applicationContext, MapsInitializer.Renderer.LATEST, null)
    }
}
