package com.spaceboy.ridebuddy

import android.app.Application

class RideBuddyApplication : Application() {
    val container: AppContainer by lazy { AppContainer(applicationContext) }

    override fun onCreate() {
        super.onCreate()
        container
    }
}
