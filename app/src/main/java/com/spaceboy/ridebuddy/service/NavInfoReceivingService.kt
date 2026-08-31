package com.spaceboy.ridebuddy.service

import com.spaceboy.ridebuddy.appContainer

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import com.google.android.libraries.mapsplatform.turnbyturn.TurnByTurnManager

/**
 * Receives turn-by-turn guidance from the Navigation SDK.
 *
 * The SDK delivers guidance by binding to a service the app declares and posting messages
 * to its [Messenger], rather than through an in-process callback — which is why this exists
 * as a Service at all. Everything it does is hand the decoded update to the shared feed,
 * from which the live screen, the cluster bridge, and the alert rules all read.
 */
class NavInfoReceivingService : Service() {
    private val manager = TurnByTurnManager.createInstance()
    private val messenger = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            if (message.what == TurnByTurnManager.MSG_NAV_INFO) {
                val info = manager.readNavInfoFromBundle(message.data)
                appContainer.navigationFeed.accept(info)
            } else super.handleMessage(message)
        }
    })

    override fun onBind(intent: Intent?): IBinder = messenger.binder
}
