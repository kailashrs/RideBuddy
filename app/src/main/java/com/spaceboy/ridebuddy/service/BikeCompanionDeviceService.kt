package com.spaceboy.ridebuddy.service

import com.spaceboy.ridebuddy.appContainer

import android.companion.CompanionDeviceService
import android.companion.DevicePresenceEvent
import android.os.Build
import androidx.annotation.RequiresApi
import com.spaceboy.ridebuddy.ble.BluetoothAddress

/** System-bound presence receiver. It deliberately does not masquerade as a wearable profile. */
class BikeCompanionDeviceService : CompanionDeviceService() {
    private val container get() = appContainer

    @Deprecated("Used by Android 12")
    override fun onDeviceAppeared(address: String) {
        if (shouldHandleLegacyPresenceCallback(Build.VERSION.SDK_INT)) reconnect(address)
    }

    @Deprecated("Used by Android 12")
    override fun onDeviceDisappeared(address: String) {
        if (shouldHandleLegacyPresenceCallback(Build.VERSION.SDK_INT)) markAbsent(address)
    }

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    override fun onDevicePresenceEvent(event: DevicePresenceEvent) {
        if (event.event == DevicePresenceEvent.EVENT_ASSOCIATION_REMOVED) {
            BikeConnectionService.disconnect(this)
            container.bikeCompanionManager.refresh()
            return
        }
        val bike = container.bikeCompanionManager.associatedBike(event.associationId) ?: return
        when (companionPresenceAction(event.event)) {
            CompanionPresenceAction.Reconnect -> BikeConnectionService.reconnect(this, bike)
            CompanionPresenceAction.MarkAbsent -> BikeConnectionService.deviceAbsent(this, bike.bluetoothAddress)
            CompanionPresenceAction.Ignore -> Unit
        }
    }

    private fun reconnect(address: String) {
        val bluetoothAddress = BluetoothAddress.parse(address) ?: return
        val bike = container.bikeCompanionManager.associatedBike()?.takeIf {
            it.bluetoothAddress == bluetoothAddress
        } ?: return
        BikeConnectionService.reconnect(this, bike)
    }

    private fun markAbsent(address: String) {
        BikeConnectionService.deviceAbsent(this, address)
    }
}

internal enum class CompanionPresenceAction {
    Reconnect,
    MarkAbsent,
    Ignore,
}

internal fun shouldHandleLegacyPresenceCallback(sdkInt: Int): Boolean =
    sdkInt < Build.VERSION_CODES.BAKLAVA

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
