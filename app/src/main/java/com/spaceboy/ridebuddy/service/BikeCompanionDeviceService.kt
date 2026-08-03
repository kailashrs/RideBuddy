package com.spaceboy.ridebuddy.service

import android.companion.CompanionDeviceService
import android.companion.DevicePresenceEvent
import android.os.Build
import androidx.annotation.RequiresApi
import com.spaceboy.ridebuddy.Rs457Application

/** System-bound presence receiver. It deliberately does not masquerade as a wearable profile. */
class BikeCompanionDeviceService : CompanionDeviceService() {
    private val container get() = (application as Rs457Application).container

    @Deprecated("Used by Android 12")
    override fun onDeviceAppeared(address: String) = reconnect(address)

    @Deprecated("Used by Android 12")
    override fun onDeviceDisappeared(address: String) = markAbsent(address)

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    override fun onDevicePresenceEvent(event: DevicePresenceEvent) {
        if (event.event == DevicePresenceEvent.EVENT_ASSOCIATION_REMOVED) {
            BikeConnectionService.disconnect(this)
            container.bikeCompanionManager.refresh()
            return
        }
        val bike = container.bikeCompanionManager.associatedBike(event.associationId) ?: return
        when (event.event) {
            DevicePresenceEvent.EVENT_BLE_APPEARED,
            DevicePresenceEvent.EVENT_BT_CONNECTED,
            -> BikeConnectionService.reconnect(this, bike)

            DevicePresenceEvent.EVENT_BLE_DISAPPEARED,
            DevicePresenceEvent.EVENT_BT_DISCONNECTED,
            -> BikeConnectionService.deviceAbsent(this, bike.address)
        }
    }

    private fun reconnect(address: String) {
        val bike = container.bikeCompanionManager.associatedBike()?.takeIf {
            it.address.equals(address, ignoreCase = true)
        } ?: return
        BikeConnectionService.reconnect(this, bike)
    }

    private fun markAbsent(address: String) {
        BikeConnectionService.deviceAbsent(this, address)
    }
}
