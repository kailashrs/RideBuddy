package com.spaceboy.ridebuddy.service

import android.companion.CompanionDeviceService
import android.companion.DevicePresenceEvent
import com.spaceboy.ridebuddy.appContainer
import com.spaceboy.ridebuddy.core.companion.AssociatedBike
import com.spaceboy.ridebuddy.core.companion.BleAppearanceDecision
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
            CompanionPresenceAction.EvaluateBleAppearance -> {
                when (container.bikeConnectionDemand.onBleAppeared()) {
                    BleAppearanceDecision.IgnoreDuplicate -> {
                        container.connectionEventJournal.record(
                            "Companion event: duplicate $eventLabel ignored",
                        )
                        return
                    }

                    BleAppearanceDecision.IgnoreWhileSuppressed -> {
                        container.connectionEventJournal.record(
                            "Companion event: $eventLabel ignored after manual disconnect",
                        )
                        return
                    }

                    BleAppearanceDecision.RequestConnection -> Unit
                }
                val state = container.bikeConnection.connectionState.value
                if (shouldRequestPresenceReconnect(state)) {
                    container.connectionEventJournal.record(
                        "Companion event: $eventLabel; requesting connection",
                    )
                    BikeConnectionService.reconnect(this, bike, automatic = true)
                } else {
                    container.connectionEventJournal.record(
                        "Companion event: $eventLabel; connection already active",
                    )
                }
            }

            CompanionPresenceAction.MarkBleAbsent -> {
                container.bikeConnectionDemand.onBleDisappeared()
                container.connectionEventJournal.record(
                    "Companion event: $eventLabel; next BLE appearance may reconnect",
                )
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
    EvaluateBleAppearance,
    MarkBleAbsent,
    KeepConnection,
    Ignore,
}

internal fun companionPresenceAction(event: Int): CompanionPresenceAction = when (event) {
    DevicePresenceEvent.EVENT_BLE_APPEARED -> CompanionPresenceAction.EvaluateBleAppearance
    DevicePresenceEvent.EVENT_BLE_DISAPPEARED -> CompanionPresenceAction.MarkBleAbsent

    // Classic Bluetooth/HID connectivity is independent of the app-owned GATT link. These
    // events are informative only and must not reset its bounded reconnect policy.
    DevicePresenceEvent.EVENT_BT_CONNECTED,
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
