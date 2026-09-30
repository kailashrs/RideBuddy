package com.spaceboy.ridebuddy.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Directions
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.TwoWheeler
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.spaceboy.ridebuddy.domain.BikeConnectionState

/**
 * First-run setup: what the app needs, why, and a way to grant each thing.
 *
 * Every page is skippable, and the app is usable without any of it — permissions are asked
 * for in context later on. What this exists for is to explain the pieces up front, since a
 * companion app that has been given nothing looks broken rather than unconfigured.
 *
 * Stateless: it renders the readiness flags it is handed and raises callbacks. The
 * permission requests themselves belong to the Activity.
 */
@Composable
fun OnboardingScreen(
    connectionState: BikeConnectionState,
    bikeAssociated: Boolean,
    nearbyDeviceAccessGranted: Boolean,
    preciseLocationGranted: Boolean,
    notificationAccessEnabled: Boolean,
    appNotificationPermissionGranted: Boolean,
    telemetryReceiving: Boolean,
    authenticated: Boolean,
    navigationConfigured: Boolean,
    onRequestNearbyDeviceAccess: () -> Unit,
    onRequestPreciseLocation: () -> Unit,
    onAssociateBike: () -> Unit,
    onOpenNotificationAccess: () -> Unit,
    onRequestAppNotificationPermission: () -> Unit,
    onSetUpNavigation: () -> Unit,
    onComplete: () -> Unit,
) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    val steps = 7
    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            LinearProgressIndicator(
                progress = { (step + 1f) / steps },
                modifier = Modifier.fillMaxWidth(),
            )
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(32.dp))
                when (step) {
                0 -> OnboardingPage(
                    icon = Icons.Outlined.TwoWheeler,
                    title = "Your motorcycle, at a glance",
                    body = "Live ride data, ride history and turn-by-turn navigation.",
                )
                1 -> OnboardingPage(
                    icon = if (nearbyDeviceAccessGranted) Icons.Outlined.CheckCircle else Icons.Outlined.Bluetooth,
                    title = "Nearby devices",
                    body = "Allow Bluetooth so RideBuddy can connect to your motorcycle.",
                    actions = if (nearbyDeviceAccessGranted) emptyList() else listOf("Allow Bluetooth" to onRequestNearbyDeviceAccess),
                    status = if (nearbyDeviceAccessGranted) "Ready" else "Needed to connect",
                )
                2 -> OnboardingPage(
                    icon = if (preciseLocationGranted) Icons.Outlined.CheckCircle else Icons.Outlined.LocationOn,
                    title = "Route recording",
                    body = "Location adds a map to each recorded ride.",
                    actions = if (preciseLocationGranted) emptyList() else listOf("Allow precise location" to onRequestPreciseLocation),
                    status = if (preciseLocationGranted) "Precise location granted" else "Optional, but required for route maps",
                )
                3 -> OnboardingPage(
                    icon = if (bikeAssociated && authenticated) Icons.Outlined.CheckCircle else Icons.Outlined.Bluetooth,
                    title = if (bikeAssociated) "Motorcycle paired" else "Pair your motorcycle",
                    body = if (bikeAssociated) {
                        "RideBuddy connects automatically when your motorcycle is available."
                    } else {
                        "Choose your motorcycle in the Bluetooth picker."
                    },
                    actions = when {
                        !bikeAssociated -> listOf("Find my bike" to onAssociateBike)
                        !authenticated -> listOf("Connect" to onAssociateBike)
                        else -> emptyList()
                    },
                    status = connectionState.onboardingLabel(),
                )
                4 -> OnboardingPage(
                    icon = if (notificationAccessEnabled) Icons.Outlined.CheckCircle else Icons.Outlined.Notifications,
                    title = "App alerts",
                    body = "Show supported app alerts on your motorcycle display.",
                    actions = buildList {
                        if (!notificationAccessEnabled) add("Enable bike alerts" to onOpenNotificationAccess)
                        if (!appNotificationPermissionGranted) add("Enable phone alerts" to onRequestAppNotificationPermission)
                    },
                    status = when {
                        notificationAccessEnabled && appNotificationPermissionGranted -> "Bike and phone alerts enabled"
                        notificationAccessEnabled -> "Bike alerts enabled"
                        appNotificationPermissionGranted -> "Phone alerts enabled"
                        else -> "Optional"
                    },
                )
                5 -> OnboardingPage(
                    icon = if (navigationConfigured) Icons.Outlined.CheckCircle else Icons.Outlined.Directions,
                    title = if (navigationConfigured) "Navigation configured" else "Google navigation",
                    body = if (navigationConfigured) {
                        "Your Google Navigation key is saved."
                    } else {
                        "Add a Google Navigation key in Settings to use turn-by-turn directions."
                    },
                    actions = if (navigationConfigured) emptyList() else listOf("Set up navigation" to onSetUpNavigation),
                )
                    else -> OnboardingPage(
                    icon = Icons.Outlined.CheckCircle,
                    title = "Setup summary",
                    body = "You can change every optional permission and feature later in Settings.",
                    readiness = listOf(
                        "Nearby devices" to nearbyDeviceAccessGranted,
                        "Motorcycle paired" to bikeAssociated,
                        "Live data from the bike" to (authenticated && telemetryReceiving),
                        "Route recording" to preciseLocationGranted,
                        "Bike app alerts" to notificationAccessEnabled,
                        "Phone alerts" to appNotificationPermissionGranted,
                        "Google navigation" to navigationConfigured,
                    ),
                    )
                }
                Spacer(Modifier.height(32.dp))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                if (step > 0) TextButton(onClick = { step-- }) { Text("Back") } else Spacer(Modifier)
                if (step < steps - 1) {
                    Button(onClick = { step++ }) { Text("Continue") }
                } else {
                    Button(onClick = onComplete) { Text("Get started") }
                }
            }
            TextButton(onClick = onComplete) { Text("Skip setup") }
        }
    }
}

/**
 * One onboarding page. [readiness] renders the checklist of what is and is not set up, and
 * [actions] the buttons that resolve each item.
 */
@Composable
private fun OnboardingPage(
    icon: ImageVector,
    title: String,
    body: String,
    actions: List<Pair<String, () -> Unit>> = emptyList(),
    status: String? = null,
    readiness: List<Pair<String, Boolean>> = emptyList(),
) {
    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(24.dp))
    Text(
        title,
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
        modifier = Modifier.semantics { heading() },
    )
    Spacer(Modifier.height(12.dp))
    Text(body, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
    status?.let {
        Spacer(Modifier.height(12.dp))
        Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
    if (readiness.isNotEmpty()) {
        Spacer(Modifier.height(20.dp))
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            readiness.forEach { (label, ready) ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (ready) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                            contentDescription = if (ready) "Ready" else "Not configured",
                            tint = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(label, modifier = Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
    if (actions.isNotEmpty()) {
        Spacer(Modifier.height(24.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            actions.forEach { (label, callback) ->
                OutlinedButton(onClick = callback) { Text(label) }
            }
        }
    }
}

/** Connection state in onboarding's plainer language, avoiding protocol terms. */
private fun BikeConnectionState.onboardingLabel(): String = when (this) {
    BikeConnectionState.Disconnected -> "Not connected"
    is BikeConnectionState.Connecting -> "Connecting"
    is BikeConnectionState.Authenticating -> "Connecting"
    is BikeConnectionState.Connected -> "Connected to $deviceName"
    is BikeConnectionState.Failed -> "Couldn't connect"
}
