package com.spaceboy.ridebuddy.ui.screens

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.CellTower
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Navigation
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.spaceboy.ridebuddy.R
import com.spaceboy.ridebuddy.data.UnitFormatter
import com.spaceboy.ridebuddy.domain.BikeConnectionState
import com.spaceboy.ridebuddy.domain.BikeIdentity
import com.spaceboy.ridebuddy.domain.BleDiagnostics
import com.spaceboy.ridebuddy.ui.labelResource
import kotlinx.coroutines.delay

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
    latestTelemetryReceivedAtElapsedRealtime: Long?,
    identity: BikeIdentity,
    diagnostics: BleDiagnostics,
    deviceAddress: String?,
    notificationAccessEnabled: Boolean,
    onReconnect: () -> Unit,
) {
    val connected = connectionState is BikeConnectionState.Connected
    val companionLinkStatus = stringResource(
        if (diagnostics.authenticated) R.string.companion_link_ready else R.string.companion_link_not_ready,
    )
    val protectionPhase = stringResource(diagnostics.protectionPhase.labelResource())
    var currentElapsedRealtime by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(connected) {
        while (connected) {
            currentElapsedRealtime = SystemClock.elapsedRealtime()
            delay(TelemetryFreshnessCheckMillis)
        }
    }
    val telemetryFresh = connected && latestTelemetryReceivedAtElapsedRealtime?.let { lastTelemetry ->
        isTelemetryFresh(lastTelemetry, currentElapsedRealtime)
    } == true

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "Vehicle Info",
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
            title = "Bike Details",
            rows = listOf(
                InfoRowItem("VIN", identity.vin ?: "Available after connection", Icons.Outlined.Fingerprint),
                InfoRowItem("Cluster software", identity.clusterSoftwareVersion ?: "Available after connection", Icons.Outlined.Memory),
                InfoRowItem(
                    "Last connected",
                    identity.lastConnectedAtMillis?.let { UnitFormatter.formatDateTime(it) } ?: "Never",
                    Icons.Outlined.Schedule,
                ),
            ),
        )

        InfoSection(
            title = "Live Link Status",
            rows = listOf(
                InfoRowItem(
                    "Telemetry",
                    stringResource(
                        if (telemetryFresh) R.string.telemetry_receiving else R.string.telemetry_waiting,
                    ),
                    Icons.Outlined.Sensors,
                ),
                InfoRowItem("Navigation", if (navigationConfigured) "Configured" else "Not configured", Icons.Outlined.Navigation),
                InfoRowItem("Companion link", companionLinkStatus, Icons.Outlined.VerifiedUser),
                InfoRowItem("Protection", protectionPhase, Icons.Outlined.VerifiedUser),
                InfoRowItem("Signal strength", diagnostics.rssi?.let { "$it dBm" } ?: "—", Icons.Outlined.CellTower),
                InfoRowItem("Telemetry rate", "%.1f Hz".format(diagnostics.telemetryHz), Icons.Outlined.Speed),
                InfoRowItem("Notification access", if (notificationAccessEnabled) "Enabled" else "Not enabled", Icons.Outlined.Notifications),
                InfoRowItem("Last error", diagnostics.lastError ?: "None", Icons.Outlined.ErrorOutline),
                InfoRowItem(
                    "Error time",
                    diagnostics.lastErrorAtMillis?.let { UnitFormatter.formatDateTime(it) } ?: "—",
                    Icons.Outlined.AccessTime,
                ),
            ),
        )

        InfoSection(
            title = "Hardware & Bluetooth",
            rows = listOf(
                InfoRowItem("Device address", deviceAddress ?: "—", Icons.Outlined.Bluetooth),
                InfoRowItem("Services ready", if (diagnostics.servicesDiscovered > 0) "Yes" else "No", Icons.Outlined.Build),
            ),
        )

        Button(
            onClick = onReconnect,
            enabled = connectionState !is BikeConnectionState.Connected,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Reconnect")
        }
    }
}

private const val TelemetryFreshnessCheckMillis = 1_000L
internal const val TelemetryFreshnessWindowMillis = 5_000L

internal fun isTelemetryFresh(receivedAtElapsedRealtime: Long, nowElapsedRealtime: Long): Boolean =
    nowElapsedRealtime - receivedAtElapsedRealtime in 0..TelemetryFreshnessWindowMillis

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
                            Box(modifier = Modifier.padding(top = 2.dp)) {
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
