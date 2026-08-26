package com.spaceboy.ridebuddy.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.BluetoothSearching
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Directions
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.TwoWheeler
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.spaceboy.ridebuddy.MaxDestinationInputLength
import com.spaceboy.ridebuddy.R
import com.spaceboy.ridebuddy.ble.TelemetryFrame
import com.spaceboy.ridebuddy.core.navigation.GuidanceState
import com.spaceboy.ridebuddy.data.ActiveRide
import com.spaceboy.ridebuddy.data.DistanceUnits
import com.spaceboy.ridebuddy.data.Ride
import com.spaceboy.ridebuddy.data.RideSample
import com.spaceboy.ridebuddy.data.UnitFormatter
import com.spaceboy.ridebuddy.domain.BikeConnectionState
import com.spaceboy.ridebuddy.ui.theme.TelemetryHero
import com.spaceboy.ridebuddy.ui.theme.statusColors
import com.spaceboy.ridebuddy.domain.BleDiagnostics
import com.spaceboy.ridebuddy.ui.components.LineChart
import com.spaceboy.ridebuddy.ui.components.LineChartScalePolicy
import com.spaceboy.ridebuddy.ui.components.Metric
import kotlin.math.roundToInt

private enum class LiveDetailLevel(val label: String) {
    Glance("Glance"),
    Ride("Ride"),
    Charts("Charts"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveScreen(
    modifier: Modifier = Modifier,
    sharedDestination: String?,
    sharedDestinationError: String?,
    isNavigationStarting: Boolean,
    connectionState: BikeConnectionState,
    telemetry: TelemetryFrame?,
    activeRide: ActiveRide?,
    liveSamples: List<RideSample>,
    diagnostics: BleDiagnostics,
    lastRide: Ride?,
    guidance: GuidanceState,
    units: DistanceUnits,
    onConnectBike: () -> Unit,
    onDisconnectBike: () -> Unit,
    onStartNavigation: (String) -> Unit,
    onOpenActiveNavigation: () -> Unit,
    onStopNavigation: () -> Unit,
    onSharedDestinationHandled: () -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    var destination by rememberSaveable { mutableStateOf(sharedDestination.orEmpty()) }
    var showLiveDetails by rememberSaveable { mutableStateOf(false) }
    var liveDetailLevel by rememberSaveable { mutableStateOf(LiveDetailLevel.Glance) }
    var showSharedConfirmation by rememberSaveable(sharedDestination, sharedDestinationError) {
        mutableStateOf(!sharedDestination.isNullOrBlank() && sharedDestinationError == null)
    }
    LaunchedEffect(sharedDestination, sharedDestinationError) {
        if (!sharedDestination.isNullOrBlank()) {
            destination = sharedDestination
            showLiveDetails = false
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ConnectionCard(connectionState, onConnectBike, onDisconnectBike)

        if (connectionState is BikeConnectionState.Connected && telemetry != null) {
            TelemetryCard(telemetry, activeRide, units, onDetails = { showLiveDetails = true })
        }

        Text(
            text = "Navigate",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(start = 16.dp)
                .semantics { heading() },
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (guidance.active) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(48.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Outlined.Directions,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(28.dp),
                                )
                            }
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = guidance.instruction.ifBlank { "Guidance active" },
                                style = MaterialTheme.typography.titleLarge,
                            )
                            if (guidance.roadName.isNotBlank()) {
                                Text(
                                    text = guidance.roadName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        guidance.distanceToDestinationMetres?.let { metres ->
                            Surface(
                                shape = MaterialTheme.shapes.medium,
                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            ) {
                                Text(
                                    text = "${UnitFormatter.distance(metres / 1000.0, units, locale)} left",
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                )
                            }
                        }
                        guidance.timeToDestinationSeconds?.let { seconds ->
                            val etaStr = UnitFormatter.formatTime(System.currentTimeMillis() + seconds * 1000L)
                            Surface(
                                shape = MaterialTheme.shapes.medium,
                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            ) {
                                Text(
                                    text = "ETA $etaStr",
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                )
                            }
                        }
                        guidance.distanceToManeuverMetres?.let { m ->
                            Surface(
                                shape = MaterialTheme.shapes.medium,
                                color = MaterialTheme.colorScheme.primaryContainer,
                            ) {
                                Text(
                                    text = stringResource(
                                        R.string.navigation_maneuver_distance,
                                        UnitFormatter.maneuverDistance(m, units, locale),
                                    ),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Button(
                            onClick = onStopNavigation,
                            modifier = Modifier.weight(1f),
                            shape = MaterialTheme.shapes.large,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            ),
                        ) {
                            Icon(Icons.Outlined.Close, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("End Route")
                        }
                        Button(
                            onClick = onOpenActiveNavigation,
                            modifier = Modifier.weight(1f),
                            shape = MaterialTheme.shapes.large,
                        ) {
                            Icon(Icons.Outlined.Directions, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.navigation_full_map))
                        }
                    }
                } else {
                    TextField(
                        value = destination,
                        onValueChange = { value ->
                            if (sharedDestinationError != null) onSharedDestinationHandled()
                            destination = value.take(MaxDestinationInputLength)
                        },
                        placeholder = { Text("Destination or Google Maps link") },
                        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                        trailingIcon = if (destination.isNotBlank()) {
                            {
                                IconButton(
                                    onClick = {
                                        destination = ""
                                        if (sharedDestination != null) onSharedDestinationHandled()
                                    },
                                ) {
                                    Icon(Icons.Outlined.Close, contentDescription = "Clear")
                                }
                            }
                        } else null,
                        isError = sharedDestinationError != null,
                        supportingText = sharedDestinationError?.let { message ->
                            { Text(message) }
                        },
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.extraLarge,
                        colors = androidx.compose.material3.TextFieldDefaults.colors(
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            disabledIndicatorColor = Color.Transparent
                        )
                    )
                    Button(
                        onClick = {
                            if (sharedDestinationError != null) onSharedDestinationHandled()
                            onStartNavigation(destination)
                        },
                        enabled = destination.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.large,
                    ) {
                        Icon(Icons.Outlined.Directions, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Start navigation")
                    }
                }
            }
        }

        Text(
            text = "Last ride",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(start = 16.dp)
                .semantics { heading() },
        )
        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = lastRide?.let {
                    "${UnitFormatter.distance(it.distanceKilometres, units, locale)} • ${formatDuration(it.durationMillis)} • ${UnitFormatter.speed(it.averageSpeedKph, units, locale)} average"
                } ?: "Your first ride summary will appear here automatically.",
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
    }

    if (showLiveDetails && !showSharedConfirmation) {
        ModalBottomSheet(
            onDismissRequest = { showLiveDetails = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            LiveDetailsSheet(
                frame = telemetry,
                activeRide = activeRide,
                samples = liveSamples,
                diagnostics = diagnostics,
                units = units,
                level = liveDetailLevel,
                onLevelChanged = { liveDetailLevel = it },
            )
        }
    }
    if (showSharedConfirmation && !sharedDestination.isNullOrBlank()) {
        ModalBottomSheet(
            onDismissRequest = {
                showSharedConfirmation = false
                onSharedDestinationHandled()
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Navigate to?", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                Text(sharedDestination, maxLines = 3, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(
                    onClick = {
                        showSharedConfirmation = false
                        onSharedDestinationHandled()
                        onStartNavigation(sharedDestination)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Start navigation") }
            }
        }
    }

    if (isNavigationStarting) {
        androidx.compose.ui.window.Dialog(onDismissRequest = {}) {
            Card {
                Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.CircularProgressIndicator()
                    Spacer(Modifier.width(16.dp))
                    Text("Finding route...")
                }
            }
        }
    }
}

@Composable
private fun ConnectionCard(
    state: BikeConnectionState,
    onConnectBike: () -> Unit,
    onDisconnectBike: () -> Unit,
) {
    val connected = state is BikeConnectionState.Connected
    val statusColors = MaterialTheme.statusColors
    val statusColor = when (state) {
        is BikeConnectionState.Connected -> statusColors.connected
        is BikeConnectionState.Connecting, is BikeConnectionState.Authenticating -> statusColors.inProgress
        is BikeConnectionState.Failed -> statusColors.error
        else -> MaterialTheme.colorScheme.outline
    }

    if (connected) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(statusColor, shape = CircleShape),
                )
                Text(
                    state.deviceName,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            TextButton(onClick = onDisconnectBike) {
                Text("Disconnect")
            }
        }
        return
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(statusColor, shape = CircleShape),
                )
                Text(
                    text = when (state) {
                        is BikeConnectionState.Connecting -> {
                            val attempt = state.reconnectAttempt
                            val max = state.maxAttempts
                            if (attempt != null && max != null) {
                                "Reconnecting (${attempt}/$max)"
                            } else {
                                "Connecting"
                            }
                        }
                        is BikeConnectionState.Authenticating -> "Verifying motorcycle link"
                        is BikeConnectionState.Failed -> "Connection Failed"
                        else -> "Disconnected"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            Icon(
                Icons.Outlined.TwoWheeler,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = when (state) {
                    is BikeConnectionState.Connecting -> {
                        val attempt = state.reconnectAttempt
                        val max = state.maxAttempts
                        if (attempt != null && max != null) {
                            "Reconnecting (${attempt}/$max)…"
                        } else {
                            "Connecting…"
                        }
                    }
                    is BikeConnectionState.Authenticating -> "Verifying motorcycle link…"
                    is BikeConnectionState.Failed -> "Connection failed"
                    else -> "Bike not connected"
                },
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = when (state) {
                    is BikeConnectionState.Failed -> state.message
                    else -> "Keep the bike switched on and nearby"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(Modifier.height(16.dp))
            if (state is BikeConnectionState.Connecting || state is BikeConnectionState.Authenticating) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                Button(onClick = onConnectBike) {
                    Icon(Icons.AutoMirrored.Outlined.BluetoothSearching, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Find my bike")
                }
            }
        }
    }
}

@Composable
private fun TelemetryCard(
    frame: TelemetryFrame,
    activeRide: ActiveRide?,
    units: DistanceUnits,
    onDetails: () -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    val displayedSpeed = UnitFormatter.chartSpeed(frame.speedKilometresPerHour, units).roundToInt()
    val rpmFraction = (frame.engineRpm / 10500f).coerceIn(0f, 1f)

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            displayedSpeed.toString(),
                            style = com.spaceboy.ridebuddy.ui.theme.TelemetryHero,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            UnitFormatter.speedUnit(units),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 12.dp),
                        )
                    }
                }
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Text(
                        text = "LIVE",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("RPM", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${frame.engineRpm} rpm", style = MaterialTheme.typography.labelMedium)
                }
                LinearProgressIndicator(
                    progress = { rpmFraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(16.dp),
                    color = if (rpmFraction > 0.85f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
                )
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Metric("Throttle", "${frame.throttlePercent}%")
                Metric("Mileage", UnitFormatter.mileage(frame.instantaneousMileageKilometresPerLitre, units, locale))
            }

            activeRide?.let {
                Text(
                    "Recording • ${UnitFormatter.distance(it.distanceKilometres, units, locale, 2)}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            TextButton(onClick = onDetails, modifier = Modifier.align(Alignment.End)) { Text("Live details") }
        }
    }
}

@Composable
private fun LiveDetailsSheet(
    frame: TelemetryFrame?,
    activeRide: ActiveRide?,
    samples: List<RideSample>,
    diagnostics: BleDiagnostics,
    units: DistanceUnits,
    level: LiveDetailLevel,
    onLevelChanged: (LiveDetailLevel) -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    val chartSamples = remember(samples) { samples.downsampleForChart() }
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 36.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Live details", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LiveDetailLevel.entries.forEach { option ->
                FilterChip(
                    selected = level == option,
                    onClick = { onLevelChanged(option) },
                    label = { Text(option.label) },
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Metric("Speed", frame?.let { UnitFormatter.speed(it.speedKilometresPerHour, units, locale) } ?: "—")
            Metric("RPM", frame?.engineRpm?.toString() ?: "—")
            Metric("Throttle", frame?.let { "${it.throttlePercent}%" } ?: "—")
        }
        Text(
            "Mileage ${frame?.let { UnitFormatter.mileage(it.instantaneousMileageKilometresPerLitre, units, locale) } ?: "— ${UnitFormatter.mileageUnit(units)}"}",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (level != LiveDetailLevel.Glance) {
            activeRide?.let {
                Text("Current ride • ${UnitFormatter.distance(it.distanceKilometres, units, locale, 2)} • ${formatDuration(System.currentTimeMillis() - it.startedAtMillis)}")
            } ?: Text("Ride recording will begin when the bike moves.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            ConnectionQuality(diagnostics, samples)
            val accelerationEvents = samples.count { it.accelerationMetresPerSecondSquared >= 3.0 }
            val brakingEvents = samples.count { it.accelerationMetresPerSecondSquared <= -3.5 }
            Text("Recent events • $accelerationEvents acceleration • $brakingEvents braking", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (level == LiveDetailLevel.Charts) {
            Text("Rolling telemetry", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            LiveChart("Speed", chartSamples.map { UnitFormatter.chartSpeed(it.speedKph, units) }, UnitFormatter.speedUnit(units))
            LiveChart("RPM", chartSamples.map { it.rpm.toDouble() }, "rpm")
            LiveChart("Throttle", chartSamples.map { it.throttlePercent.toDouble() }, "%")
            LiveChart(
                "Mileage",
                chartSamples.map { UnitFormatter.mileageValue(it.mileageKilometresPerLitre, units, locale) },
                UnitFormatter.mileageUnit(units),
            )
            Text(
                "${samples.size} recent packets • ${diagnostics.notificationsReceived} notifications received • ${diagnostics.malformedTelemetryFrames} malformed frames",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ConnectionQuality(diagnostics: BleDiagnostics, samples: List<RideSample>) {
    val loss = packetLossEstimate(samples)
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Connection quality", style = MaterialTheme.typography.titleMedium)
            Text("Signal ${diagnostics.rssi?.let { "$it dBm" } ?: "—"} • %.1f Hz".format(diagnostics.telemetryHz))
            Text("Estimated packet gaps ${loss?.let { "$it%" } ?: "collecting data"}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LiveChart(title: String, values: List<Double?>, unit: String) {
    val color = MaterialTheme.colorScheme.primary
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(values.lastOrNull { it != null }?.let { "Latest %.1f %s".format(it, unit) } ?: "Waiting for samples", color = MaterialTheme.colorScheme.onSurfaceVariant)
            LineChart(
                values = values,
                height = 100.dp,
                topPadding = 8.dp,
                color = color,
                contentDescription = "$title chart with ${values.size} samples",
                scalePolicy = LineChartScalePolicy.AutoRange,
                clampNegativeValues = false,
                smooth = true,
                strokeWidth = 3f,
                fillAlpha = 0.3f,
            )
        }
    }
}

private fun packetLossEstimate(samples: List<RideSample>): Int? {
    if (samples.size < 4) return null
    val intervals = samples.zipWithNext { first, second -> (second.timestampMillis - first.timestampMillis).coerceAtLeast(1) }
    val baseline = intervals.sorted()[intervals.size / 2].coerceAtLeast(1)
    val expected = ((samples.last().timestampMillis - samples.first().timestampMillis) / baseline + 1).coerceAtLeast(1)
    return (((expected - samples.size).coerceAtLeast(0) * 100.0) / expected).roundToInt().coerceIn(0, 100)
}

/** Limits a live chart to a drawable amount of data without losing its beginning or latest sample. */
private fun List<RideSample>.downsampleForChart(maxPoints: Int = 120): List<RideSample> {
    if (size <= maxPoints) return this
    val lastIndex = lastIndex
    return List(maxPoints) { index -> this[index * lastIndex / (maxPoints - 1)] }
}
