package com.spaceboy.ridebuddy.core.companion

import android.content.Context
import androidx.core.content.edit

/** Whether the app may connect on its own, without the rider asking. */
internal enum class AutomaticConnectionDemand {
    Allowed,

    /**
     * The rider ended the session or its attempt budget was exhausted. Automatic connection stays off until the
     * motorcycle actually goes away — otherwise the presence callback that is still firing
     * for a bike parked in range would immediately undo their choice.
     */
    SuppressedUntilBleDisappears,
}

internal data class BikeConnectionDemandState(
    val automaticConnectionDemand: AutomaticConnectionDemand = AutomaticConnectionDemand.Allowed,
    /** An observed disappearance whose matching appearance has not arrived yet. */
    val awaitingBleAppearance: Boolean = false,
)

internal enum class BikeConnectionDemandEvent {
    ExplicitConnect,
    ManualDisconnect,
    ConnectionAttemptsExhausted,
    BleAppeared,
    BleDisappeared,
}

/**
 * What to do about an appearance callback.
 *
 * A disappearance can precede retry exhaustion. Remember that edge so the motorcycle's
 * next real appearance can start a new cycle while duplicate present callbacks stay suppressed.
 */
internal enum class BleAppearanceDecision {
    RequestConnection,

    /** The session ended and the bike has not left since. */
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

    BikeConnectionDemandEvent.ManualDisconnect,
    BikeConnectionDemandEvent.ConnectionAttemptsExhausted,
    -> BikeConnectionDemandTransition(
        state.copy(
            automaticConnectionDemand = AutomaticConnectionDemand.SuppressedUntilBleDisappears,
        ),
    )

    BikeConnectionDemandEvent.BleDisappeared -> BikeConnectionDemandTransition(
        state.copy(
            automaticConnectionDemand = AutomaticConnectionDemand.Allowed,
            awaitingBleAppearance = true,
        ),
    )

    BikeConnectionDemandEvent.BleAppeared -> {
        val nextState = state.copy(
            automaticConnectionDemand = if (state.awaitingBleAppearance) {
                AutomaticConnectionDemand.Allowed
            } else state.automaticConnectionDemand,
            awaitingBleAppearance = false,
        )
        val decision =
            if (nextState.automaticConnectionDemand == AutomaticConnectionDemand.SuppressedUntilBleDisappears) {
                BleAppearanceDecision.IgnoreWhileSuppressed
            } else {
                BleAppearanceDecision.RequestConnection
            }
        BikeConnectionDemandTransition(nextState, decision)
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
        awaitingBleAppearance = preferences.getBoolean(KeyAwaitingBleAppearance, false),
    )

    /** The rider asked to connect. Always clears suppression. */
    fun allowExplicitConnection() {
        transition(BikeConnectionDemandEvent.ExplicitConnect)
    }

    /** The rider disconnected deliberately, from the UI or the notification. */
    fun suppressAutomaticConnections() {
        transition(BikeConnectionDemandEvent.ManualDisconnect)
    }

    /** Duplicate appearance callbacks cannot grant another budget after three failures. */
    fun onConnectionAttemptsExhausted() {
        transition(BikeConnectionDemandEvent.ConnectionAttemptsExhausted)
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
     * Persists both suppression and an unmatched disappearance. A process restart must
     * neither grant duplicate callbacks a new budget nor discard the next actual appearance.
     */
    private fun transition(event: BikeConnectionDemandEvent): BikeConnectionDemandTransition = synchronized(lock) {
        val previous = state
        bikeConnectionDemandTransition(state, event).also { transition ->
            state = transition.state
            if (state != previous) {
                preferences.edit {
                    putBoolean(
                        KeySuppressed,
                        state.automaticConnectionDemand ==
                            AutomaticConnectionDemand.SuppressedUntilBleDisappears,
                    )
                    putBoolean(KeyAwaitingBleAppearance, state.awaitingBleAppearance)
                }
            }
        }
    }

    private companion object {
        const val PreferencesName = "bike_connection_demand"
        const val KeySuppressed = "automatic_connection_suppressed"
        const val KeyAwaitingBleAppearance = "awaiting_ble_appearance"
    }
}
