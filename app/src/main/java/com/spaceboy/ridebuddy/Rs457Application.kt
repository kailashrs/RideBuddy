package com.spaceboy.ridebuddy

import android.app.Application

class Rs457Application : Application() {
    val container: AppContainer by lazy { AppContainer(applicationContext) }

    override fun onCreate() {
        super.onCreate()
        container
    }
}
