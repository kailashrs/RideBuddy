package com.spaceboy.ridebuddy.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** User-visible notification actions enter the process here without starting a new FGS. */
class BikeConnectionActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == BikeConnectionService.ActionDisconnect) {
            BikeConnectionService.disconnect(context)
        }
    }
}
