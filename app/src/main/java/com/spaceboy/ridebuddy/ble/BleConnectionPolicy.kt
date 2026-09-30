package com.spaceboy.ridebuddy.ble

import com.spaceboy.ridebuddy.domain.BikeConnectionState
import com.spaceboy.ridebuddy.domain.BikeConnectionTarget

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
