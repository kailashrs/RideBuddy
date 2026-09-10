package com.spaceboy.ridebuddy.ble

import android.bluetooth.BluetoothGatt
import com.spaceboy.ridebuddy.domain.BikeConnectionState

/**
 * How many times the app tries to reach the motorcycle before it gives up, counting the first
 * attempt.
 *
 * Bounded on purpose, and deliberately small. Retrying a bike that has been switched off drains
 * the phone battery for no benefit, and giving up is not a quiet state: it ends the ride, clears
 * everything staged for the cluster, and stops the route. Three attempts is long enough to ride
 * through a dropout at the edge of range and short enough that a rider who has parked and walked
 * away has their ride saved while they are still nearby.
 */
internal const val MaxConnectionAttempts = 3

private const val MaxReconnectDelayMillis = 30_000L

/**
 * Backoff before the reconnect numbered [reconnectAttempt] — zero-based, so 0 schedules the
 * second attempt overall — or null once the budget is spent, which the caller reads as "stop
 * retrying".
 *
 * The delay doubles from one second and is capped at 30 s. Neither the cap nor the shift guard
 * bites at three attempts; both are there so raising the budget cannot silently overflow the
 * shift or schedule an absurd wait.
 */
internal fun reconnectDelayMillis(reconnectAttempt: Int): Long? {
    if (reconnectAttempt !in 0..MaxConnectionAttempts - 2) return null
    return minOf(MaxReconnectDelayMillis, 1_000L shl minOf(reconnectAttempt, 5))
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
