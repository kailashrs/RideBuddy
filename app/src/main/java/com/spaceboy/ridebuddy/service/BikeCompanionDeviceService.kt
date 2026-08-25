package com.spaceboy.ridebuddy.service

import android.companion.CompanionDeviceService
import android.companion.DevicePresenceEvent
import com.spaceboy.ridebuddy.appContainer
import com.spaceboy.ridebuddy.core.companion.AssociatedBike
import com.spaceboy.ridebuddy.domain.BikeConnectionState

/** System-bound presence receiver. It deliberately does not masquerade as a wearable profile. */
class BikeCompanionDeviceService : CompanionDeviceService() {
    private val container get() = appContainer

    override fun onDevicePresenceEvent(event: DevicePresenceEvent) {
        if (event.event == DevicePresenceEvent.EVENT_ASSOCIATION_REMOVED) {
            container.connectionEventJournal.record(
                "Companion event: association ${event.associationId} removed; disconnecting",
            )
            BikeConnectionService.disconnect(this)
            container.bikeCompanionManager.refresh()
            return
        }
        val eventLabel = companionPresenceEventLabel(event.event)
        val bike: AssociatedBike = container.bikeCompanionManager.associatedBike(event.associationId) ?: run {
            container.connectionEventJournal.record(
                "Companion event: $eventLabel ignored; association ${event.associationId} is unavailable",
            )
            return
        }
        when (companionPresenceAction(event.event)) {
            CompanionPresenceAction.EnsureConnected -> {
                val state = container.bikeConnection.connectionState.value
                if (shouldRequestPresenceReconnect(state)) {
                    container.connectionEventJournal.record(
                        "Companion event: $eventLabel; requesting connection",
                    )
                    BikeConnectionService.reconnect(this, bike)
                } else {
                    container.connectionEventJournal.record(
                        "Companion event: $eventLabel; connection already active",
                    )
                }
            }

            CompanionPresenceAction.KeepConnection -> container.connectionEventJournal.record(
                "Companion event: $eventLabel; leaving GATT ownership unchanged",
            )

            CompanionPresenceAction.Ignore -> container.connectionEventJournal.record(
                "Companion event: $eventLabel ignored",
            )
        }
    }
}

internal enum class CompanionPresenceAction {
    EnsureConnected,
    KeepConnection,
    Ignore,
}

internal fun companionPresenceAction(event: Int): CompanionPresenceAction = when (event) {
    DevicePresenceEvent.EVENT_BLE_APPEARED,
    DevicePresenceEvent.EVENT_BT_CONNECTED,
    -> CompanionPresenceAction.EnsureConnected

    // BLE scanning and Bluetooth connectivity are independent CDM presence sources. Neither
    // negative event is authoritative for the app-owned GATT link; its callback and bounded
    // reconnect policy remain the source of truth.
    DevicePresenceEvent.EVENT_BLE_DISAPPEARED,
    DevicePresenceEvent.EVENT_BT_DISCONNECTED,
    -> CompanionPresenceAction.KeepConnection
    else -> CompanionPresenceAction.Ignore
}

internal fun shouldRequestPresenceReconnect(state: BikeConnectionState): Boolean =
    state is BikeConnectionState.Disconnected || state is BikeConnectionState.Failed

internal fun companionPresenceEventLabel(event: Int): String = when (event) {
    DevicePresenceEvent.EVENT_BLE_APPEARED -> "BLE appeared"
    DevicePresenceEvent.EVENT_BLE_DISAPPEARED -> "BLE disappeared"
    DevicePresenceEvent.EVENT_BT_CONNECTED -> "Bluetooth connected"
    DevicePresenceEvent.EVENT_BT_DISCONNECTED -> "Bluetooth disconnected"
    DevicePresenceEvent.EVENT_SELF_MANAGED_APPEARED -> "self-managed device appeared"
    DevicePresenceEvent.EVENT_SELF_MANAGED_DISAPPEARED -> "self-managed device disappeared"
    DevicePresenceEvent.EVENT_ASSOCIATION_REMOVED -> "association removed"
    else -> "unknown presence event $event"
}
