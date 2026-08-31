package com.spaceboy.ridebuddy.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.spaceboy.ridebuddy.R
import com.spaceboy.ridebuddy.ble.BleCaptureState
import com.spaceboy.ridebuddy.data.UnitFormatter
import com.spaceboy.ridebuddy.domain.BikeConnectionState
import com.spaceboy.ridebuddy.domain.BikeIdentity
import com.spaceboy.ridebuddy.ui.LiveTelemetryStreams
import com.spaceboy.ridebuddy.ble.BleCharacteristics
import com.spaceboy.ridebuddy.ui.labelResource
import java.util.UUID

private fun UUID.shortNameForUi(): String = toString().takeLast(4)

/**
 * Live protocol readout: link state, handshake phase, GATT counters, recent frames and
 * events, and the shareable support report.
 *
 * A developer-facing screen reached from settings, and the intended first stop when a
 * connection misbehaves — [BikeConnectionState.Failed] carries one message, while the
 * journal here carries the sequence that led to it.
 */
@Composable
fun DiagnosticsScreen(
    live: LiveTelemetryStreams,
    bleCapture: BleCaptureState,
    connectionState: BikeConnectionState,
    identity: BikeIdentity,
    deviceAddress: String?,
    notificationAccessEnabled: Boolean,
    onExport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Diagnostics is a live readout, so it is the one screen that should follow the frame rate.
    val diagnostics = live.diagnostics.collectAsStateWithLifecycle().value
    val companionLinkStatus = stringResource(
        if (diagnostics.authenticated) R.string.companion_link_ready else R.string.companion_link_not_ready,
    )
    val protectionPhase = stringResource(diagnostics.protectionPhase.labelResource())
    val protectionPath = diagnostics.protectionPath?.let { stringResource(it.labelResource()) } ?: "—"

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(
            text = "Connection diagnostics",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() },
        )

        DiagnosticSection(
            title = "Connection",
            rows = listOf(
                "State" to connectionState.diagnosticLabel(),
                "Device" to (deviceAddress ?: "Not associated"),
                "Last successful link" to (identity.lastConnectedAtMillis?.let(UnitFormatter::formatDateTime)
                    ?: "No successful link recorded"),
                "Companion link" to companionLinkStatus,
                "Protection phase" to protectionPhase,
                "Protection path" to protectionPath,
                "System bond" to when (diagnostics.bonded) {
                    true -> "Bonded"
                    false -> "Not bonded"
                    null -> "Unknown"
                },
                "RSSI" to (diagnostics.rssi?.let { "$it dBm" } ?: "—"),
                "ATT MTU" to (diagnostics.attMtu?.let { "$it bytes" } ?: "—"),
                "GATT services" to if (diagnostics.servicesDiscovered > 0) {
                    "${diagnostics.servicesDiscovered} discovered"
                } else {
                    "—"
                },
                "Notification access" to if (notificationAccessEnabled) "Enabled" else "Disabled",
            ),
        )

        DiagnosticSection(
            title = "Motorcycle identity",
            rows = listOf(
                "VIN" to (identity.vin ?: "Not reported"),
                "Cluster software" to (identity.clusterSoftwareVersion ?: "Not reported"),
            ),
        )

        DiagnosticSection(
            title = "GATT activity",
            rows = listOf(
                "Active operation" to (diagnostics.activeGattOperation ?: "—"),
                "Notifications" to diagnostics.notificationsReceived.toString(),
                "Descriptor writes" to diagnostics.descriptorWritesCompleted.toString(),
                "Characteristic writes" to diagnostics.writesCompleted.toString(),
                "Telemetry rate" to "%.1f Hz".format(diagnostics.telemetryHz),
                "Last frame" to (diagnostics.lastFrameAtMillis?.let(UnitFormatter::formatDateTime) ?: "—"),
                "Estimated malformed frames" to diagnostics.malformedTelemetryFrames.toString(),
                "Dropped ride frames" to diagnostics.droppedRawTelemetryFrames.toString(),
                "BLE capture" to if (bleCapture.enabled) {
                    "On • ${bleCapture.entries.size} packets"
                } else {
                    "Off"
                },
            ),
        )

        DiagnosticSection(
            title = "Errors",
            rows = listOf(
                "Last error" to (diagnostics.lastError ?: "None"),
                "Error time" to (diagnostics.lastErrorAtMillis?.let(UnitFormatter::formatDateTime) ?: "—"),
                "Error category" to (diagnostics.lastFailure?.category?.name ?: "—"),
                "Error context" to (diagnostics.lastFailure?.contextLine() ?: "—"),
                "Automatic retries" to (diagnostics.suppressionReason ?: "Active"),
            ),
        )

        diagnostics.lastSuccessfulLink?.let { link ->
            DiagnosticSection(
                title = "Last successful link",
                rows = listOf(
                    "Session" to link.sessionId.toString(),
                    "ATT MTU" to (link.attMtu?.let { "$it bytes" } ?: "—"),
                    "GATT services" to link.servicesDiscovered.toString(),
                    "Established" to (link.establishedAtMillis?.let(UnitFormatter::formatDateTime) ?: "—"),
                    "Held for" to (link.durationMillis?.let { "${it / 1_000}s" } ?: "—"),
                ),
            )
        }

        if (diagnostics.serviceSnapshot.isNotEmpty()) {
            DiagnosticSection(
                title = "GATT snapshot",
                rows = diagnostics.serviceSnapshot.mapIndexed { index, value -> "${index + 1}" to value },
            )
        }
        if (diagnostics.recentEvents.isNotEmpty()) {
            Text(
                text = "Recent events",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(start = 16.dp)
                    .semantics { heading() },
            )
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    diagnostics.recentEvents.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        Button(onClick = onExport, modifier = Modifier.fillMaxWidth()) { Text("Export report") }
    }
}

/**
 * Connection state in protocol terms, unlike the rider-facing labels elsewhere. Reconnects
 * show their attempt number, which is what distinguishes a link retrying from one stuck.
 */
private fun BikeConnectionState.diagnosticLabel(): String = when (this) {
    BikeConnectionState.Disconnected -> "Disconnected"
    is BikeConnectionState.Connecting -> reconnectAttempt?.let { attempt ->
        "Reconnecting ($attempt/${maxAttempts ?: "?"})"
    } ?: "Connecting"
    is BikeConnectionState.Authenticating -> "Authenticating"
    is BikeConnectionState.Connected -> "Connected"
    is BikeConnectionState.Failed -> "Failed: $message"
}

@Composable
private fun DiagnosticSection(title: String, rows: List<Pair<String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(start = 16.dp)
                .semantics { heading() },
        )
        DiagnosticCard(rows)
    }
}

@Composable
private fun DiagnosticCard(rows: List<Pair<String, String>>) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column {
            rows.forEachIndexed { index, (label, value) ->
                if (index > 0) {
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(
                        text = value,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}
