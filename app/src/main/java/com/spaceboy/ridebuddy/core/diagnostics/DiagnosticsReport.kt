package com.spaceboy.ridebuddy.core.diagnostics

import com.spaceboy.ridebuddy.data.UnitFormatter
import com.spaceboy.ridebuddy.domain.BikeConnectionState
import com.spaceboy.ridebuddy.domain.BikeIdentity
import com.spaceboy.ridebuddy.domain.BleDiagnostics

private const val Unknown = "unknown"

/**
 * Renders the shareable support report.
 *
 * Label strings are passed in already resolved so the report itself stays free of Android
 * resources and can be asserted on directly.
 */
internal fun diagnosticsReport(
    connectionState: BikeConnectionState,
    diagnostics: BleDiagnostics,
    identity: BikeIdentity,
    protectionPhaseLabel: String,
    protectionPathLabel: String?,
): String = buildString {
    appendLine("RideBuddy diagnostics")
    appendLine("Connection: $connectionState")
    appendLine("Companion link ready: ${diagnostics.authenticated}")
    appendLine("Protection phase: $protectionPhaseLabel")
    appendLine("Protection path: ${protectionPathLabel ?: Unknown}")
    appendLine("Bonded: ${diagnostics.bonded ?: Unknown}")
    appendLine("RSSI: ${diagnostics.rssi ?: Unknown} dBm")
    appendLine("Telemetry rate: %.2f Hz".format(diagnostics.telemetryHz))
    appendLine("ATT MTU: ${diagnostics.attMtu ?: Unknown}")
    appendLine("Services: ${diagnostics.servicesDiscovered}")
    appendLine("Notifications: ${diagnostics.notificationsReceived}")
    appendLine("Characteristic writes: ${diagnostics.writesCompleted}")
    appendLine("VIN: ${identity.vin ?: Unknown}")
    appendLine("Cluster software: ${identity.clusterSoftwareVersion ?: Unknown}")
    appendLine(
        "Last successful link: " +
            (identity.lastConnectedAtMillis?.let(UnitFormatter::formatDateTime) ?: "never"),
    )
    appendLine("Last error: ${diagnostics.lastError ?: "none"}")
    diagnostics.suppressionReason?.let { reason -> appendLine("Automatic retries paused: $reason") }
    appendLine("\nGATT snapshot")
    diagnostics.serviceSnapshot.forEach(::appendLine)
    appendLine("\nRecent events")
    diagnostics.recentEvents.forEach(::appendLine)
    appendLine("\nRecent frames")
    diagnostics.recentFrames.forEach(::appendLine)
}
