package com.spaceboy.ridebuddy.core.companion

import android.content.Context
import androidx.core.content.edit

/** Whether the app may connect on its own, without the rider asking. */
internal enum class AutomaticConnectionDemand {
    Allowed,

    /**
     * The rider disconnected deliberately. Automatic connection stays off until the
     * motorcycle actually goes away — otherwise the presence callback that is still firing
     * for a bike parked in range would immediately undo their choice.
     */
    SuppressedUntilBleDisappears,
}

/** Last observed presence. [Unknown] until the first callback of the process arrives. */
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

/** What to do about an appearance callback. */
internal enum class BleAppearanceDecision {
    RequestConnection,

    /**
     * Already recorded as present, so there is nothing new to act on.
     *
     * Not because the platform polls — `onDevicePresenceEvent` is edge-triggered and fires on
     * change, not while the bike sits in range. This covers an appearance arriving with no
     * disappearance between, which is what a re-registered listener or a replayed state
     * produces.
     */
    IgnoreDuplicate,

    /** The rider disconnected on purpose and the bike has not left since. */
    IgnoreWhileSuppressed,
}

internal data class BikeConnectionDemandTransition(
    val state: BikeConnectionDemandState,
    val appearanceDecision: BleAppearanceDecision? = null,
)

/**
 * The whole policy as one pure function, so it can be exercised directly rather than
 * through storage and platform callbacks.
 *
 * A disappearance clears suppression as well as recording absence: the bike genuinely left,
 * so the rider's earlier disconnect no longer describes a situation that still exists.
 */
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

    /** The rider asked to connect. Always clears suppression. */
    fun allowExplicitConnection() {
        transition(BikeConnectionDemandEvent.ExplicitConnect)
    }

    /** The rider disconnected deliberately, from the UI or the notification. */
    fun suppressAutomaticConnections() {
        transition(BikeConnectionDemandEvent.ManualDisconnect)
    }

    /** Whether a launch-time or service-driven automatic attempt is permitted. */
    fun canStartAutomaticConnection(): Boolean = synchronized(lock) {
        state.automaticConnectionDemand == AutomaticConnectionDemand.Allowed
    }

    fun onBleAppeared(): BleAppearanceDecision =
        requireNotNull(transition(BikeConnectionDemandEvent.BleAppeared).appearanceDecision)

    fun onBleDisappeared() {
        transition(BikeConnectionDemandEvent.BleDisappeared)
    }

    /**
     * Applies an event and persists the demand flag when it changed. Only that flag is
     * stored: presence is re-established by the platform's callbacks on the next launch,
     * whereas a forgotten suppression would silently reconnect a bike the rider had
     * disconnected.
     */
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
