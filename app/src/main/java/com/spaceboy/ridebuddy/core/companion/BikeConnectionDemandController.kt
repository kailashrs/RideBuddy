package com.spaceboy.ridebuddy.core.companion

import android.content.Context
import androidx.core.content.edit

internal enum class AutomaticConnectionDemand {
    Allowed,
    SuppressedUntilBleDisappears,
}

internal enum class ObservedBlePresence {
    Unknown,
    Present,
    Absent,
}

internal data class BikeConnectionDemandState(
    val automaticConnectionDemand: AutomaticConnectionDemand = AutomaticConnectionDemand.Allowed,
    val blePresence: ObservedBlePresence = ObservedBlePresence.Unknown,
)

internal enum class BikeConnectionDemandEvent {
    ExplicitConnect,
    ManualDisconnect,
    BleAppeared,
    BleDisappeared,
}

internal enum class BleAppearanceDecision {
    RequestConnection,
    IgnoreDuplicate,
    IgnoreWhileSuppressed,
}

internal data class BikeConnectionDemandTransition(
    val state: BikeConnectionDemandState,
    val appearanceDecision: BleAppearanceDecision? = null,
)

/** Pure transition function shared by the runtime controller and its regression tests. */
internal fun bikeConnectionDemandTransition(
    state: BikeConnectionDemandState,
    event: BikeConnectionDemandEvent,
): BikeConnectionDemandTransition = when (event) {
    BikeConnectionDemandEvent.ExplicitConnect -> BikeConnectionDemandTransition(
        state.copy(automaticConnectionDemand = AutomaticConnectionDemand.Allowed),
    )

    BikeConnectionDemandEvent.ManualDisconnect -> BikeConnectionDemandTransition(
        state.copy(
            automaticConnectionDemand = AutomaticConnectionDemand.SuppressedUntilBleDisappears,
        ),
    )

    BikeConnectionDemandEvent.BleDisappeared -> BikeConnectionDemandTransition(
        state.copy(
            automaticConnectionDemand = AutomaticConnectionDemand.Allowed,
            blePresence = ObservedBlePresence.Absent,
        ),
    )

    BikeConnectionDemandEvent.BleAppeared -> {
        val duplicate = state.blePresence == ObservedBlePresence.Present
        val decision = when {
            state.automaticConnectionDemand == AutomaticConnectionDemand.SuppressedUntilBleDisappears ->
                BleAppearanceDecision.IgnoreWhileSuppressed

            duplicate -> BleAppearanceDecision.IgnoreDuplicate
            else -> BleAppearanceDecision.RequestConnection
        }
        BikeConnectionDemandTransition(
            state.copy(blePresence = ObservedBlePresence.Present),
            decision,
        )
    }
}

/**
 * Owns the user's connection intent separately from transient GATT state.
 *
 * A notification or UI disconnect remains authoritative while the bike is still advertising.
 * The suppression is persisted so a process restart cannot turn a queued companion-presence
 * callback into an immediate reconnect. A genuine BLE disappearance arms automatic connection
 * for the next appearance, while an explicit Connect action always overrides suppression.
 */
internal class BikeConnectionDemandController(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PreferencesName,
        Context.MODE_PRIVATE,
    )
    private val lock = Any()
    private var state = BikeConnectionDemandState(
        automaticConnectionDemand = if (preferences.getBoolean(KeySuppressed, false)) {
            AutomaticConnectionDemand.SuppressedUntilBleDisappears
        } else {
            AutomaticConnectionDemand.Allowed
        },
    )

    fun allowExplicitConnection() {
        transition(BikeConnectionDemandEvent.ExplicitConnect)
    }

    fun suppressAutomaticConnections() {
        transition(BikeConnectionDemandEvent.ManualDisconnect)
    }

    fun canStartAutomaticConnection(): Boolean = synchronized(lock) {
        state.automaticConnectionDemand == AutomaticConnectionDemand.Allowed
    }

    fun onBleAppeared(): BleAppearanceDecision =
        requireNotNull(transition(BikeConnectionDemandEvent.BleAppeared).appearanceDecision)

    fun onBleDisappeared() {
        transition(BikeConnectionDemandEvent.BleDisappeared)
    }

    private fun transition(event: BikeConnectionDemandEvent): BikeConnectionDemandTransition = synchronized(lock) {
        val previousDemand = state.automaticConnectionDemand
        bikeConnectionDemandTransition(state, event).also { transition ->
            state = transition.state
            if (state.automaticConnectionDemand != previousDemand) {
                preferences.edit {
                    putBoolean(
                        KeySuppressed,
                        state.automaticConnectionDemand ==
                            AutomaticConnectionDemand.SuppressedUntilBleDisappears,
                    )
                }
            }
        }
    }

    private companion object {
        const val PreferencesName = "bike_connection_demand"
        const val KeySuppressed = "automatic_connection_suppressed"
    }
}
