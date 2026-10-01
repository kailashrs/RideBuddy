package com.spaceboy.ridebuddy.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Directions
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.TwoWheeler
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.spaceboy.ridebuddy.domain.BikeConnectionState

/**
 * First-run setup: welcome, permissions, pairing, navigation.
 *
 * Every step is skippable and the app works without any of it — permissions are asked for in
 * context later on. This exists to explain the pieces up front, since a companion app that has
 * been given nothing looks broken rather than unconfigured.
 *
 * Stateless: it renders the readiness flags it is handed and raises callbacks. The permission
 * requests themselves belong to the Activity.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    connectionState: BikeConnectionState,
    bikeAssociated: Boolean,
    nearbyDeviceAccessGranted: Boolean,
    preciseLocationGranted: Boolean,
    appNotificationPermissionGranted: Boolean,
    telemetryReceiving: Boolean,
    authenticated: Boolean,
    navigationConfigured: Boolean,
    onRequestNearbyDeviceAccess: () -> Unit,
    onRequestPreciseLocation: () -> Unit,
    onAssociateBike: () -> Unit,
    onRequestAppNotificationPermission: () -> Unit,
    onSetUpNavigation: () -> Unit,
    onComplete: () -> Unit,
) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    val last = OnboardingSteps - 1
    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                actions = { if (step < last) TextButton(onClick = onComplete) { Text("Skip") } },
            )
        },
        bottomBar = {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (step > 0) TextButton(onClick = { step-- }) { Text("Back") } else Box(Modifier)
                if (step < last) Button(onClick = { step++ }) { Text("Continue") }
                else Button(onClick = onComplete) { Text("Get started") }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            LinearProgressIndicator(
                progress = { (step + 1f) / OnboardingSteps },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                when (step) {
                    0 -> Intro(
                        Icons.Outlined.TwoWheeler,
                        "Your motorcycle, at a glance",
                        "Live ride data, automatic ride history, and turn-by-turn directions on the bike's display.",
                    )
                    1 -> {
                        Intro(Icons.Outlined.VerifiedUser, "Permissions", "Allow what you want to use. You can change these later in Settings.")
                        Permission(Icons.Outlined.Bluetooth, "Nearby devices", "Required to connect to the bike",
                            nearbyDeviceAccessGranted, onRequestNearbyDeviceAccess)
                        Permission(Icons.Outlined.LocationOn, "Precise location", "Maps each ride and powers navigation",
                            preciseLocationGranted, onRequestPreciseLocation)
                        Permission(Icons.Outlined.NotificationsActive, "Phone notifications", "Riding alerts on this phone",
                            appNotificationPermissionGranted, onRequestAppNotificationPermission)
                    }
                    2 -> {
                        Intro(
                            if (bikeAssociated && authenticated) Icons.Outlined.CheckCircle else Icons.Outlined.Bluetooth,
                            if (bikeAssociated) "Motorcycle paired" else "Pair your motorcycle",
                            when {
                                bikeAssociated && authenticated && telemetryReceiving -> "Connected and receiving live data."
                                bikeAssociated -> "RideBuddy connects automatically whenever the bike is nearby."
                                else -> "Turn the ignition on, then choose your bike from the list."
                            },
                        )
                        Text(connectionState.onboardingLabel(), style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary)
                        when {
                            !bikeAssociated -> Button(onClick = onAssociateBike) { Text("Find my bike") }
                            !authenticated -> FilledTonalButton(onClick = onAssociateBike) { Text("Connect") }
                        }
                    }
                    else -> {
                        Intro(
                            if (navigationConfigured) Icons.Outlined.CheckCircle else Icons.Outlined.Directions,
                            "Navigation",
                            if (navigationConfigured) "Your Google Navigation key is saved."
                            else "Turn-by-turn directions use your own Google Navigation key. You can add it now or later in Settings.",
                        )
                        if (!navigationConfigured) FilledTonalButton(onClick = onSetUpNavigation) { Text("Add key") }
                    }
                }
            }
        }
    }
}

private const val OnboardingSteps = 4

@Composable
private fun Intro(icon: ImageVector, title: String, body: String) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
        Icon(icon, contentDescription = null, modifier = Modifier.padding(20.dp).size(40.dp))
    }
    Text(
        title,
        style = MaterialTheme.typography.headlineMedium,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = 24.dp).semantics { heading() },
    )
    Text(
        body,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = 24.dp),
    )
}

/** One permission: what it is for, and either Allow or a check once granted. */
@Composable
private fun Permission(icon: ImageVector, title: String, purpose: String, granted: Boolean, onAllow: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(purpose) },
        leadingContent = { Icon(icon, contentDescription = null) },
        trailingContent = {
            if (granted) Icon(Icons.Outlined.CheckCircle, contentDescription = "Allowed", tint = MaterialTheme.colorScheme.primary)
            else FilledTonalButton(onClick = onAllow) { Text("Allow") }
        },
    )
}

/** Connection state in onboarding's plainer language, avoiding protocol terms. */
private fun BikeConnectionState.onboardingLabel(): String = when (this) {
    BikeConnectionState.Disconnected -> "Not connected"
    is BikeConnectionState.Connecting -> "Connecting…"
    is BikeConnectionState.Authenticating -> "Connecting…"
    is BikeConnectionState.Connected -> "Connected to $deviceName"
    is BikeConnectionState.Failed -> "Couldn't connect"
}
