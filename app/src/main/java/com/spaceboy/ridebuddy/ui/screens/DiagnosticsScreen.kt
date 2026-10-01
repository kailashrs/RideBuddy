package com.spaceboy.ridebuddy.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spaceboy.ridebuddy.R
import com.spaceboy.ridebuddy.ble.BleCaptureState
import com.spaceboy.ridebuddy.data.UnitFormatter
import com.spaceboy.ridebuddy.domain.BikeConnectionState
import com.spaceboy.ridebuddy.ui.MainScreenActions
import com.spaceboy.ridebuddy.ui.MainScreenState
import com.spaceboy.ridebuddy.ui.components.SectionHeader
import com.spaceboy.ridebuddy.ui.components.SettingsRow
import com.spaceboy.ridebuddy.ui.components.SettingsSwitchRow
import com.spaceboy.ridebuddy.ui.labelResource

/**
 * Connection details: whether to keep the history, a shareable report, then a live readout of
 * the link — state, handshake phase, GATT counters, errors and recent events. The first stop when a connection
 * misbehaves — [BikeConnectionState.Failed] carries one message; the journal here carries the
 * sequence that led to it.
 */
@Composable
fun DiagnosticsScreen(
    state: MainScreenState,
    actions: MainScreenActions,
    modifier: Modifier = Modifier,
) {
    // A live readout, so this is the one screen that follows the frame rate.
    val diagnostics = state.live.diagnostics.collectAsStateWithLifecycle().value
    val rideMetrics = state.live.rideMetrics.collectAsStateWithLifecycle().value
    val capture = state.bleCapture
    val identity = state.identity
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
        SettingsSwitchRow(
            "Keep connection history",
            "Save recent connection activity across app restarts",
            state.settings.persistConnectionDiagnostics,
            icon = Icons.Outlined.History,
            onCheckedChange = actions.settingsActions.onPersistConnectionDiagnosticsChanged,
        )
        SettingsRow("Share connection report", "State, errors and recent events as text",
            icon = Icons.Outlined.IosShare, onClick = actions.onExportDiagnostics)

        Header("Connection")
        Readout("State", state.connectionState.diagnosticLabel())
        Readout("Device", state.bikeAssociation.bike?.address?.toString()?.uppercase() ?: "Not paired")
        Readout("Last successful link", identity.lastConnectedAtMillis?.let(UnitFormatter::formatDateTime) ?: "None recorded")
        Readout("Companion link", stringResource(if (diagnostics.authenticated) R.string.companion_link_ready else R.string.companion_link_not_ready))
        Readout("Protection phase", stringResource(diagnostics.protectionPhase.labelResource()))
        Readout("Protection path", diagnostics.protectionPath?.let { stringResource(it.labelResource()) } ?: "—")
        Readout("System bond", when (diagnostics.bonded) { true -> "Bonded"; false -> "Not bonded"; null -> "Unknown" })
        Readout("RSSI", diagnostics.rssi?.let { "$it dBm" } ?: "—")
        Readout("ATT MTU", diagnostics.attMtu?.let { "$it bytes" } ?: "—")
        Readout("GATT services", diagnostics.servicesDiscovered.takeIf { it > 0 }?.toString() ?: "—")
        Readout("Notification access", if (state.notificationAccessEnabled) "Enabled" else "Disabled")
        Readout("VIN", identity.vin ?: "Not reported")
        Readout("Cluster software", identity.clusterSoftwareVersion ?: "Not reported")

        Header("GATT activity")
        Readout("Notifications", diagnostics.notificationsReceived.toString())
        Readout("Characteristic writes", diagnostics.writesCompleted.toString())
        Readout("Telemetry rate", "%.1f Hz".format(diagnostics.telemetryHz))
        Readout("Last frame", diagnostics.lastFrameAtMillis?.let(UnitFormatter::formatDateTime) ?: "—")
        Readout("Estimated malformed frames", diagnostics.malformedTelemetryFrames.toString())
        Readout("Dropped ride frames", diagnostics.droppedRawTelemetryFrames.toString())
        Readout("Estimated packet gaps", rideMetrics.estimatedPacketGapPercent?.let { "$it%" } ?: "—")

        Header("Errors")
        Readout("Last error", diagnostics.lastError ?: "None")
        Readout("Error time", diagnostics.lastErrorAtMillis?.let(UnitFormatter::formatDateTime) ?: "—")
        Readout("Automatic retries", diagnostics.suppressionReason ?: "Active")

        if (diagnostics.serviceSnapshot.isNotEmpty()) {
            Header("GATT snapshot")
            diagnostics.serviceSnapshot.forEachIndexed { index, value -> Readout("${index + 1}", value) }
        }
        if (diagnostics.recentEvents.isNotEmpty()) {
            Header("Recent events")
            diagnostics.recentEvents.forEach {
                Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }
        }
    }
}

@Composable
private fun Header(title: String) = SectionHeader(title, Modifier.padding(horizontal = 16.dp))

/** A dense label/value line; a readout this long would be unreadable as two-line list items. */
@Composable
private fun Readout(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
    }
}

/** Connection state in protocol terms; reconnects show their attempt number. */
private fun BikeConnectionState.diagnosticLabel(): String = when (this) {
    BikeConnectionState.Disconnected -> "Disconnected"
    is BikeConnectionState.Connecting -> reconnectAttempt?.let { "Connecting ($it/${maxAttempts ?: "?"})" } ?: "Connecting"
    is BikeConnectionState.Authenticating -> "Authenticating"
    is BikeConnectionState.Connected -> "Connected"
    is BikeConnectionState.Failed -> "Failed: $message"
}

/** The captured packets, newest first. The privacy note is here because this is where they get shared. */
@Composable
internal fun BleCaptureDialog(capture: BleCaptureState, onShare: () -> Unit, onClear: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Captured packets") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Kept in memory until cleared or the app closes.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (capture.entries.isEmpty()) Text("Turn on capture, then reproduce what you want to inspect.")
                capture.entries.asReversed().forEach { Text(it.format(), style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = onShare, enabled = capture.entries.isNotEmpty()) { Text("Share") } },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onClear, enabled = capture.entries.isNotEmpty()) { Text("Clear") }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
    )
}
