package com.spaceboy.ridebuddy

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.spaceboy.ridebuddy.domain.BikeConnectionState

/** The action reflects live connection readiness, including authentication. */
@Composable
internal fun NavigationStartControls(
    routeReady: Boolean,
    connection: BikeConnectionState,
    paired: Boolean,
    pairing: Boolean,
    onGo: () -> Unit,
    onConnect: () -> Unit,
) {
    val connected = connection is BikeConnectionState.Connected
    val connecting = pairing || connection is BikeConnectionState.Connecting ||
        connection is BikeConnectionState.Authenticating
    if (routeReady && !connected && !connecting) Text("Connect to start")
    val waiting = !routeReady || connecting
    Button(
        onClick = if (connected) onGo else onConnect,
        enabled = !waiting,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (waiting) {
            CircularProgressIndicator(Modifier.size(ButtonDefaults.IconSize), strokeWidth = 2.dp)
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
        }
        Text(when {
            !routeReady -> "Finding route…"
            connecting -> "Connecting…"
            connected -> "Go"
            paired -> "Connect"
            else -> "Find my bike"
        })
    }
}
