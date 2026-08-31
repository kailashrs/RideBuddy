package com.spaceboy.ridebuddy.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Entry point for the notification's Disconnect action.
 *
 * A receiver rather than a service target on purpose: routing the action straight to the
 * foreground service would mean starting a foreground service from the background, which
 * the platform forbids in exactly the situation this action is used in.
 */
class BikeConnectionActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == BikeConnectionService.ActionDisconnect) {
            BikeConnectionService.disconnect(context)
        }
    }
}
