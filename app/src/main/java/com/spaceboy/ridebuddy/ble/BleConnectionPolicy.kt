package com.spaceboy.ridebuddy.ble

import android.bluetooth.BluetoothGatt
import com.spaceboy.ridebuddy.domain.BikeConnectionState

/**
 * Total attempts in one connection cycle, including the first attempt.
 * A successfully authenticated session ends the cycle; losing it starts a new one.
 */
internal const val MaxConnectionAttempts = 3

/**
 * Delay after [attemptsStarted] connection attempts, or null when the budget is spent.
 * Zero is the first recovery after an established link drops; it receives the same short
 * backoff as the first failed attempt instead of reconnecting in the callback itself.
 */
internal fun reconnectDelayMillis(attemptsStarted: Int): Long? = when (attemptsStarted) {
    0, 1 -> 1_000L
    2 -> 2_000L
    else -> null
}

/** Counts actual attempts, so the initial request cannot sit outside the retry budget. */
internal class ConnectionAttemptBudget {
    var attemptsStarted: Int = 0
        private set

    fun beginAttempt(): Boolean {
        if (attemptsStarted >= MaxConnectionAttempts) return false
        attemptsStarted++
        return true
    }

    fun reset() {
        attemptsStarted = 0
    }

    fun nextDelayMillis(): Long? = reconnectDelayMillis(attemptsStarted)
}

/**
 * Whether a launch-time automatic connection may start.
 *
 * [AndroidBikeConnection] owns automatic retries. Once its bounded schedule has been exhausted,
 * only a fresh BLE appearance or an explicit user action may resume; recreating the UI must not
 * quietly hand the stack a new retry budget.
 */
internal fun shouldAutoConnectOnLaunch(state: BikeConnectionState): Boolean = when (state) {
    BikeConnectionState.Disconnected -> true
    is BikeConnectionState.Failed -> !state.retriesExhausted
    is BikeConnectionState.Connecting,
    is BikeConnectionState.Authenticating,
    is BikeConnectionState.Connected,
    -> false
}

/**
 * Whether a connect request should actually start a new attempt.
 *
 * A request naming a different motorcycle always starts one. A request naming the bike
 * already in play is only honoured when nothing is in flight; otherwise it would tear
 * down a connection that is midway through discovery or authentication and restart it
 * from scratch.
 */
internal fun shouldStartConnection(
    currentTarget: BikeConnectionTarget?,
    requestedTarget: BikeConnectionTarget,
    state: BikeConnectionState,
): Boolean = currentTarget?.address != requestedTarget.address ||
    state is BikeConnectionState.Disconnected ||
    state is BikeConnectionState.Failed

/**
 * Human-readable name for a GATT status code, for logs and the diagnostics screen.
 *
 * The numeric literals are HCI-level disconnect reasons that Android forwards verbatim
 * without exposing constants for them; they are the codes that actually distinguish "the
 * bike went out of range" from "the bike hung up on us" when reading a capture.
 */
internal fun gattConnectionStatusLabel(status: Int): String = when (status) {
    BluetoothGatt.GATT_SUCCESS -> "success"
    0x08 -> "link supervision timeout"
    0x13 -> "peer terminated connection"
    0x16 -> "local host terminated connection"
    0x3E -> "connection failed to establish"
    0x85 -> "generic GATT error"
    BluetoothGatt.GATT_CONNECTION_TIMEOUT -> "GATT connection timeout"
    BluetoothGatt.GATT_FAILURE -> "GATT failure"
    else -> "unknown"
}
