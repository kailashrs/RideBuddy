package com.spaceboy.ridebuddy.ui.screens

import android.os.PowerManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.spaceboy.ridebuddy.domain.BikeConnectionState
import com.spaceboy.ridebuddy.domain.BikeIdentity

private data class InfoRowItem(
    val label: String,
    val value: String,
    val icon: ImageVector,
)

@Composable
fun InfoScreen(
    modifier: Modifier = Modifier,
    navigationConfigured: Boolean,
    connectionState: BikeConnectionState,
    identity: BikeIdentity,
    notificationAccessEnabled: Boolean,
    onReconnect: () -> Unit,
) {
    val connected = connectionState is BikeConnectionState.Connected
    val context = LocalContext.current
    val backgroundAccess = context.getSystemService(PowerManager::class.java)
        ?.isIgnoringBatteryOptimizations(context.packageName)
        ?: false
    val missingIdentityLabel = if (connected) {
        "Not reported by motorcycle"
    } else {
        "Available after a successful connection"
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
                text = "Motorcycle & setup",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = when (connectionState) {
                    is BikeConnectionState.Connected -> "Connected to ${connectionState.deviceName}"
                    is BikeConnectionState.Authenticating -> "Verifying motorcycle link"
                    is BikeConnectionState.Connecting -> "Connecting"
                    is BikeConnectionState.Failed -> connectionState.message
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
                    if (navigationConfigured) "Ready" else "Set up in Settings",
                    Icons.Outlined.Navigation,
                ),
                InfoRowItem(
                    "Calls & alerts",
                    if (notificationAccessEnabled) "Ready" else "Enable notification access in Settings",
                    Icons.Outlined.Notifications,
                ),
                InfoRowItem(
                    "Background connection",
                    if (backgroundAccess) {
                        "Allowed to keep running"
                    } else {
                        "Battery optimization may pause reconnects"
                    },
                    Icons.Outlined.BatteryAlert,
                ),
            ),
        )

        Button(
            onClick = onReconnect,
            enabled = !connected,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Reconnect")
        }
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
                    ListItem(
                        headlineContent = { Text(row.label) },
                        supportingContent = {
                            Text(
                                text = row.value,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        leadingContent = {
                            Box(modifier = Modifier.padding(top = 4.dp)) {
                                Icon(
                                    imageVector = row.icon,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }
        }
    }
}
