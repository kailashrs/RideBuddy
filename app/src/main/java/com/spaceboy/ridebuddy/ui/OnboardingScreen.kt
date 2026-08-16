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

@Composable
fun OnboardingScreen(
    connectionState: BikeConnectionState,
    bikeAssociated: Boolean,
    associatedBikeLabel: String?,
    nearbyDeviceAccessGranted: Boolean,
    preciseLocationGranted: Boolean,
    notificationAccessEnabled: Boolean,
    appNotificationPermissionGranted: Boolean,
    legacyCallPermissionGranted: Boolean,
    telemetryReceiving: Boolean,
    authenticated: Boolean,
    navigationConfigured: Boolean,
    onRequestNearbyDeviceAccess: () -> Unit,
    onRequestPreciseLocation: () -> Unit,
    onConnectBike: () -> Unit,
    onOpenNotificationAccess: () -> Unit,
    onRequestAppNotificationPermission: () -> Unit,
    onEnableLegacyCalls: () -> Unit,
    onSetUpNavigation: () -> Unit,
    onComplete: () -> Unit,
) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    val steps = 7
    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(28.dp),
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
                    body = "Live telemetry, automatic ride history and Google-powered turn-by-turn guidance in one quiet companion.",
                )
                1 -> OnboardingPage(
                    icon = if (nearbyDeviceAccessGranted) Icons.Outlined.CheckCircle else Icons.Outlined.Bluetooth,
                    title = "Nearby devices",
                    body = "Allow Bluetooth access so the companion can reconnect to the motorcycle and exchange telemetry and TFT commands.",
                    actions = if (nearbyDeviceAccessGranted) emptyList() else listOf("Allow nearby devices" to onRequestNearbyDeviceAccess),
                    status = if (nearbyDeviceAccessGranted) "Permission granted" else "Permission needed to connect",
                )
                2 -> OnboardingPage(
                    icon = if (preciseLocationGranted) Icons.Outlined.CheckCircle else Icons.Outlined.LocationOn,
                    title = "Route recording",
                    body = "Precise location adds distance, route previews and start/end areas to automatic ride history. Weather checks also use the current riding location when enabled.",
                    actions = if (preciseLocationGranted) emptyList() else listOf("Allow precise location" to onRequestPreciseLocation),
                    status = if (preciseLocationGranted) "Precise location granted" else "Optional, but required for route maps",
                )
                3 -> OnboardingPage(
                    icon = if (bikeAssociated && authenticated) Icons.Outlined.CheckCircle else Icons.Outlined.Bluetooth,
                    title = if (bikeAssociated) "Confirm your motorcycle" else "Associate your motorcycle",
                    body = if (bikeAssociated) {
                        "Your motorcycle (${associatedBikeLabel ?: "saved motorcycle"}) is saved. RideBuddy will automatically connect whenever you switch on the ignition."
                    } else {
                        "Pair your motorcycle using Android's device manager. This allows RideBuddy to automatically reconnect whenever your bike is turned on."
                    },
                    actions = when {
                        !bikeAssociated -> listOf("Choose motorcycle" to onConnectBike)
                        !authenticated -> listOf("Reconnect motorcycle" to onConnectBike)
                        else -> emptyList()
                    },
                    status = connectionState.onboardingLabel(),
                )
                4 -> OnboardingPage(
                    icon = if (notificationAccessEnabled) Icons.Outlined.CheckCircle else Icons.Outlined.Notifications,
                    title = "Calls and alerts",
                    body = "Grant notification access to display incoming caller names, call controls, and weather alerts directly on your motorcycle screen.",
                    actions = buildList {
                        if (!notificationAccessEnabled) add("Enable TFT access" to onOpenNotificationAccess)
                        if (!appNotificationPermissionGranted) add("Enable riding alerts" to onRequestAppNotificationPermission)
                        if (!legacyCallPermissionGranted) add("Enable legacy call fallback" to onEnableLegacyCalls)
                    },
                    status = when {
                        notificationAccessEnabled && appNotificationPermissionGranted -> "Standard call actions and riding alerts are ready"
                        notificationAccessEnabled -> "TFT access ready; phone-side alerts are off"
                        else -> "Optional access is not enabled"
                    },
                )
                5 -> OnboardingPage(
                    icon = if (navigationConfigured) Icons.Outlined.CheckCircle else Icons.Outlined.Directions,
                    title = if (navigationConfigured) "Navigation configured" else "Google navigation",
                    body = if (navigationConfigured) {
                        "Your Google Navigation key is securely stored on this device to enable turn-by-turn route guidance on your dashboard."
                    } else {
                        "Navigation is optional. Set up a Google Navigation key anytime in settings, or enjoy telemetry and ride logging without it."
                    },
                    actions = if (navigationConfigured) emptyList() else listOf("Set up navigation" to onSetUpNavigation),
                )
                    else -> OnboardingPage(
                    icon = Icons.Outlined.CheckCircle,
                    title = "Setup summary",
                    body = "You can change every optional permission and feature later in Settings.",
                    readiness = listOf(
                        "Nearby access" to nearbyDeviceAccessGranted,
                        "Bike association" to bikeAssociated,
                        "Companion link ready" to (authenticated && telemetryReceiving),
                        "Route recording" to preciseLocationGranted,
                        "TFT calls and alerts" to notificationAccessEnabled,
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
                    Button(onClick = onComplete) { Text("Get Started") }
                }
            }
            TextButton(onClick = onComplete) { Text("Skip setup") }
        }
    }
}

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
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            readiness.forEach { (label, ready) ->
                androidx.compose.material3.OutlinedCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
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

private fun BikeConnectionState.onboardingLabel(): String = when (this) {
    BikeConnectionState.Disconnected -> "Not connected"
    BikeConnectionState.Scanning -> "Looking for your bike"
    is BikeConnectionState.Connecting -> "Connecting"
    is BikeConnectionState.Authenticating -> "Verifying motorcycle link"
    is BikeConnectionState.Connected -> "Connected to $deviceName"
    is BikeConnectionState.Failed -> message
}
