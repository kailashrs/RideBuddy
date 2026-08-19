package com.spaceboy.ridebuddy.service

import com.spaceboy.ridebuddy.appContainer

import android.companion.CompanionDeviceService
import android.companion.DevicePresenceEvent
import com.spaceboy.ridebuddy.core.companion.AssociatedBike

/** System-bound presence receiver. It deliberately does not masquerade as a wearable profile. */
class BikeCompanionDeviceService : CompanionDeviceService() {
    private val container get() = appContainer

    override fun onDevicePresenceEvent(event: DevicePresenceEvent) {
        if (event.event == DevicePresenceEvent.EVENT_ASSOCIATION_REMOVED) {
            BikeConnectionService.disconnect(this)
            container.bikeCompanionManager.refresh()
            return
        }
        val bike: AssociatedBike = container.bikeCompanionManager.associatedBike(event.associationId) ?: return
        when (companionPresenceAction(event.event)) {
            CompanionPresenceAction.Reconnect -> BikeConnectionService.reconnect(this, bike)
            CompanionPresenceAction.MarkAbsent -> BikeConnectionService.deviceAbsent(this, bike.bluetoothAddress)
            CompanionPresenceAction.Ignore -> Unit
        }
    }
}

internal enum class CompanionPresenceAction {
    Reconnect,
    MarkAbsent,
    Ignore,
}

internal fun companionPresenceAction(event: Int): CompanionPresenceAction = when (event) {
    DevicePresenceEvent.EVENT_BLE_APPEARED,
    DevicePresenceEvent.EVENT_BT_CONNECTED,
    -> CompanionPresenceAction.Reconnect

    DevicePresenceEvent.EVENT_BLE_DISAPPEARED -> CompanionPresenceAction.MarkAbsent

    // AOSP reports this before it knows whether the device is still advertising over BLE. The
    // GATT owner already performs bounded reconnects, so treating it as physical absence cancels
    // recovery while the motorcycle may still be nearby.
    DevicePresenceEvent.EVENT_BT_DISCONNECTED -> CompanionPresenceAction.Ignore
    else -> CompanionPresenceAction.Ignore
}
