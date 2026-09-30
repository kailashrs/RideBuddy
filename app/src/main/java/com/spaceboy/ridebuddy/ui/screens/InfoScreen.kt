package com.spaceboy.ridebuddy.ui.screens

import android.os.PowerManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Navigation
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.spaceboy.ridebuddy.ui.components.SettingsRow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.spaceboy.ridebuddy.domain.BikeConnectionState
import com.spaceboy.ridebuddy.domain.BikeIdentity

/** One label/value row, with an icon that identifies the kind of information at a glance. */
private data class InfoRowItem(
    val label: String,
    val value: String,
    val icon: ImageVector,
    val onClick: (() -> Unit)? = null,
)

/**
 * What is set up and what is not: the motorcycle's identity, link state, and which optional
 * features are ready. The rider-facing counterpart to the diagnostics screen — the same
 * underlying state, in plain terms and without the protocol detail.
 */
@Composable
fun InfoScreen(
    modifier: Modifier = Modifier,
    navigationConfigured: Boolean,
    connectionState: BikeConnectionState,
    identity: BikeIdentity,
    notificationAccessEnabled: Boolean,
    onOpenNavigationSettings: () -> Unit,
    onOpenNotificationAccess: () -> Unit,
    onOpenAppPermissions: () -> Unit,
) {
    val connected = connectionState is BikeConnectionState.Connected
    val context = LocalContext.current
    var backgroundAccess by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        backgroundAccess = context.getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(context.packageName) == true
    }
    val missingIdentityLabel = if (connected) {
        "Not reported"
    } else {
        "Connect to view"
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = when (connectionState) {
                    is BikeConnectionState.Connected -> "Connected to ${connectionState.deviceName}"
                    is BikeConnectionState.Authenticating -> "Connecting"
                    is BikeConnectionState.Connecting -> "Connecting"
                    is BikeConnectionState.Failed -> "Couldn't connect"
                    else -> "Not connected"
                },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        InfoSection(
            title = "Motorcycle",
            rows = listOf(
                InfoRowItem("VIN", identity.vin ?: missingIdentityLabel, Icons.Outlined.Fingerprint),
                InfoRowItem(
                    "Cluster software",
                    identity.clusterSoftwareVersion ?: missingIdentityLabel,
                    Icons.Outlined.Memory,
                ),
            ),
        )

        InfoSection(
            title = "App setup",
            rows = listOf(
                InfoRowItem(
                    "Navigation",
                    if (navigationConfigured) "Key configured" else "Set up navigation",
                    Icons.Outlined.Navigation,
                    onOpenNavigationSettings,
                ),
                InfoRowItem(
                    "App alerts",
                    if (notificationAccessEnabled) "Enabled" else "Set up alerts",
                    Icons.Outlined.Notifications,
                    onOpenNotificationAccess,
                ),
                InfoRowItem(
                    "Battery use",
                    if (backgroundAccess) {
                        "Unrestricted"
                    } else {
                        "Optimized"
                    },
                    Icons.Outlined.BatteryAlert,
                    onOpenAppPermissions,
                ),
            ),
        )
    }
}

@Composable
private fun InfoSection(title: String, rows: List<InfoRowItem>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(start = 16.dp)
                .semantics { heading() },
        )
        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Column {
                rows.forEachIndexed { index, row ->
                    if (index > 0) {
                        HorizontalDivider(Modifier.padding(start = 56.dp))
                    }
                    SettingsRow(icon = row.icon, title = row.label, supportingText = row.value,
                        onClick = row.onClick)
                }
            }
        }
    }
}
