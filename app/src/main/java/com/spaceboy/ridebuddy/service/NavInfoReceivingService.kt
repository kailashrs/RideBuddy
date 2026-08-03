package com.spaceboy.ridebuddy.service

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import com.google.android.libraries.mapsplatform.turnbyturn.TurnByTurnManager
import com.spaceboy.ridebuddy.Rs457Application

class NavInfoReceivingService : Service() {
    private val manager = TurnByTurnManager.createInstance()
    private val messenger = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            if (message.what == TurnByTurnManager.MSG_NAV_INFO) {
                val info = manager.readNavInfoFromBundle(message.data)
                (application as Rs457Application).container.navigationFeed.accept(info)
            } else super.handleMessage(message)
        }
    })

    override fun onBind(intent: Intent?): IBinder = messenger.binder
}
