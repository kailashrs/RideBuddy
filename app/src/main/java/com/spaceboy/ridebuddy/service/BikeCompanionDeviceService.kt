package com.spaceboy.ridebuddy.service

import android.companion.CompanionDeviceService
import android.companion.DevicePresenceEvent
import com.spaceboy.ridebuddy.appContainer
import com.spaceboy.ridebuddy.core.companion.AssociatedBike
import com.spaceboy.ridebuddy.core.companion.BleAppearanceDecision
import com.spaceboy.ridebuddy.domain.BikeConnectionState
import com.spaceboy.ridebuddy.domain.ConnectionAttemptTrigger

/**
 * Receives presence callbacks for the associated motorcycle, so the app can connect when it
 * comes into range without running continuously.
 *
 * The system binds this and calls it directly; it holds no state of its own. Decisions
 * belong to [com.spaceboy.ridebuddy.core.companion.BikeConnectionDemandController], and
 * every branch is journalled — presence events are otherwise invisible after the fact, and
 * they are the usual explanation for why a reconnect did or did not happen.
 */
class BikeCompanionDeviceService : CompanionDeviceService() {
    private val container get() = appContainer

    /**
     * Handles one presence event.
     *
     * An association removed elsewhere — from system settings, say — is handled first and
     * unconditionally: there is no longer a motorcycle to be present, so the link is
     * dropped and the local record reconciled.
     */
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
                val state = container.bikeConnection.connectionState.value
                if (!shouldRequestPresenceReconnect(state)) {
                    // The appearance is deliberately not handed to the demand controller here.
                    // Consuming it marks the bike Present, which makes the next one a duplicate,
                    // while the attempt being deferred to can still fail its way to
                    // retriesExhausted — the very state a fresh appearance exists to resume,
                    // reached with the appearance already spent. That strands the app in Failed
                    // with the bike advertising beside it.
                    container.connectionEventJournal.record(
                        "Companion event: $eventLabel; an attempt is already in flight",
                    )
                    return
                }
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
                container.connectionEventJournal.record(
                    "Companion event: $eventLabel; requesting connection",
                )
                BikeConnectionService.reconnect(
                    this,
                    bike,
                    trigger = ConnectionAttemptTrigger.PresenceAppearance,
                )
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

/** What a presence event means for the app-owned GATT link. */
internal enum class CompanionPresenceAction {
    /** The bike is advertising; a connection may be warranted. */
    EvaluateBleAppearance,

    /** The bike has gone; record it, which re-arms automatic connection. */
    MarkBleAbsent,

    /** Informative only. Must not disturb a link or a retry budget. */
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

/**
 * Whether an appearance should trigger a connection. Only from a settled state: an attempt
 * already in flight will either succeed or run its own backoff, and starting a second would
 * tear down the first.
 */
internal fun shouldRequestPresenceReconnect(state: BikeConnectionState): Boolean =
    state is BikeConnectionState.Disconnected || state is BikeConnectionState.Failed

/** Readable name for the journal, so a presence event is legible in a diagnostics export. */
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
