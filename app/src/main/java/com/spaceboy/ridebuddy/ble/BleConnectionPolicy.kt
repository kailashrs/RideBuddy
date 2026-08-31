package com.spaceboy.ridebuddy.ble

import android.bluetooth.BluetoothGatt
import com.spaceboy.ridebuddy.domain.BikeConnectionState

/**
 * How many automatic reconnects a connection may make before it gives up and reports
 * failure. Bounded on purpose: an unbounded retry loop against a bike that has been
 * switched off drains the phone battery for no benefit.
 */
internal const val MaxReconnectAttempts = 6

private const val MaxReconnectDelayMillis = 30_000L

/**
 * Backoff before reconnect attempt [attempt] (zero-based), or null once the budget is
 * spent — which the caller reads as "stop retrying".
 *
 * The delay doubles from one second and is capped at 30 s. The inner `minOf(attempt, 5)`
 * guards the shift itself so the expression cannot overflow if the attempt bound is ever
 * raised without revisiting this line.
 */
internal fun reconnectDelayMillis(attempt: Int): Long? {
    if (attempt !in 0 until MaxReconnectAttempts) return null
    return minOf(MaxReconnectDelayMillis, 1_000L shl minOf(attempt, 5))
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
