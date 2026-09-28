package com.spaceboy.ridebuddy

import com.spaceboy.ridebuddy.domain.BikeConnectionState

/** A disconnected preview stays usable; a connection attempt that ends retires the session. */
internal class NavigationConnectionPolicy(
    autoStartPending: Boolean,
    connectionSessionSeen: Boolean = false,
) {
    var autoStartPending = autoStartPending
        private set
    var connectionSessionSeen = connectionSessionSeen
        private set

    fun onConnectionState(state: BikeConnectionState): Boolean {
        if (state !is BikeConnectionState.Connected) autoStartPending = false
        val terminal = connectionSessionSeen &&
            (state is BikeConnectionState.Failed || state is BikeConnectionState.Disconnected)
        if (state is BikeConnectionState.Connecting || state is BikeConnectionState.Authenticating ||
            state is BikeConnectionState.Connected
        ) connectionSessionSeen = true
        return terminal
    }

    /** Consumed when the route is found. Reconnecting later never starts it implicitly. */
    fun consumeAutoStart(state: BikeConnectionState): Boolean {
        val start = autoStartPending && state is BikeConnectionState.Connected
        autoStartPending = false
        return start
    }
}

internal enum class GuidanceStartResult { Started, ConnectionRequired, UpdatesUnavailable }

/** Both entry checks read the live state; preparing SDK updates must not bypass a lost link. */
internal fun startConnectedGuidance(
    connectionState: () -> BikeConnectionState,
    registerUpdates: () -> Boolean,
    unregisterUpdates: () -> Unit,
    startGuidance: () -> Unit,
): GuidanceStartResult {
    if (connectionState() !is BikeConnectionState.Connected) return GuidanceStartResult.ConnectionRequired
    if (!registerUpdates()) return GuidanceStartResult.UpdatesUnavailable
    if (connectionState() !is BikeConnectionState.Connected) {
        unregisterUpdates()
        return GuidanceStartResult.ConnectionRequired
    }
    startGuidance()
    return GuidanceStartResult.Started
}
