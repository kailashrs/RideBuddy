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
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.spaceboy.ridebuddy.ble.BleCaptureState
import com.spaceboy.ridebuddy.domain.BleDiagnostics
import java.text.DateFormat
import java.util.Date

@Composable
fun DiagnosticsScreen(
    diagnostics: BleDiagnostics,
    bleCapture: BleCaptureState,
    deviceAddress: String?,
    notificationAccessEnabled: Boolean,
    onExport: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
        DiagnosticCard(
            listOf(
                "Device" to (deviceAddress ?: "Not associated"),
                "Authentication" to if (diagnostics.authenticated) "Verified" else "Not verified",
                "Notification listener" to if (notificationAccessEnabled) "Enabled" else "Disabled",
                "RSSI" to (diagnostics.rssi?.let { "$it dBm" } ?: "—"),
                "Telemetry" to "%.1f Hz".format(diagnostics.telemetryHz),
                "Estimated malformed frames" to diagnostics.malformedTelemetryFrames.toString(),
                "MTU" to (diagnostics.negotiatedMtu?.toString() ?: "—"),
                "Last error" to (diagnostics.lastError ?: "None"),
                "Error time" to (diagnostics.lastErrorAtMillis?.let { DateFormat.getDateTimeInstance().format(Date(it)) } ?: "—"),
                "BLE capture" to if (bleCapture.enabled) "On • ${bleCapture.entries.size} packets" else "Off",
            ),
        )
        if (diagnostics.serviceSnapshot.isNotEmpty()) {
            Text(
                text = "GATT snapshot",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(start = 16.dp)
                    .semantics { heading() },
            )
            DiagnosticCard(diagnostics.serviceSnapshot.mapIndexed { index, value -> "${index + 1}" to value })
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
                    horizontalArrangement = Arrangement.SpaceBetween,
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
