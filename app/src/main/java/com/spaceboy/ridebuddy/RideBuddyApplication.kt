package com.spaceboy.ridebuddy

import android.app.Application

/**
 * Process entry point. Owns the single [AppContainer] every component reaches through
 * [appContainer]. Maps initialize at their UI entry points, including on a cold start.
 */
class RideBuddyApplication : Application() {
    val container: AppContainer by lazy { AppContainer(applicationContext) }

    override fun onCreate() {
        super.onCreate()
        // Force construction here rather than on first use. Several entry points are
        // system-driven — the presence service, the call service — and the first
        // touch could otherwise happen on a callback thread mid-work.
        container
    }
}
