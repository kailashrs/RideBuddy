package com.spaceboy.ridebuddy.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BluetoothConnected
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalContentColor
import com.spaceboy.ridebuddy.ui.components.SectionHeader
import androidx.compose.material.icons.outlined.Directions
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.TwoWheeler
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.res.stringResource
import com.spaceboy.ridebuddy.MaxDestinationInputLength
import com.spaceboy.ridebuddy.R
import com.spaceboy.ridebuddy.ble.TelemetryFrame
import com.spaceboy.ridebuddy.core.navigation.GuidanceState
import com.spaceboy.ridebuddy.data.ActiveRide
import com.spaceboy.ridebuddy.data.DistanceUnits
import com.spaceboy.ridebuddy.data.Ride
import com.spaceboy.ridebuddy.data.TelemetryChartData
import com.spaceboy.ridebuddy.data.telemetryChartData
import com.spaceboy.ridebuddy.data.UnitFormatter
import com.spaceboy.ridebuddy.domain.BikeConnectionState
import com.spaceboy.ridebuddy.ui.LiveTelemetryStreams
import com.spaceboy.ridebuddy.ui.components.LineChart
import com.spaceboy.ridebuddy.ui.components.Metric
import kotlin.math.roundToInt

/**
 * The riding screen: connection status, live telemetry, and navigation entry.
 *
 * Telemetry arrives as flows rather than values (see
 * [com.spaceboy.ridebuddy.ui.LiveTelemetryStreams]) and is collected as late as possible,
 * so a frame at 4 Hz recomposes the gauges and nothing else.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveScreen(
    modifier: Modifier = Modifier,
    sharedDestination: String?,
    sharedDestinationError: String?,
    isNavigationStarting: Boolean,
    connectionState: BikeConnectionState,
    bikeAssociated: Boolean,
    pairingInProgress: Boolean,
    live: LiveTelemetryStreams,
    lastRide: Ride?,
    guidance: GuidanceState,
    units: DistanceUnits,
    onConnectBike: () -> Unit,
    onDisconnectBike: () -> Unit,
    onEndRide: () -> Unit,
    onRetryRideSave: () -> Unit,
    onStartNavigation: (String) -> Unit,
    onOpenActiveNavigation: () -> Unit,
    onStopNavigation: () -> Unit,
    onSharedDestinationHandled: () -> Unit,
    onCancelNavigationStart: () -> Unit,
    onRideSelected: (Ride) -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    // Keep edits when a share is consumed; a newer share is applied by the effect below.
    var destination by rememberSaveable { mutableStateOf(sharedDestination.orEmpty()) }
    var showLiveDetails by rememberSaveable { mutableStateOf(false) }
    // Applies a share that arrives while this screen is already composed; the initial value above
    // covers first composition and state restore. Blank is ignored rather than assigned, so
    // clearing the share leaves the field alone.
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
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ConnectionCard(
            state = connectionState,
            bikeAssociated = bikeAssociated,
            pairingInProgress = pairingInProgress,
            onConnectBike = onConnectBike,
            onDisconnectBike = onDisconnectBike,
        )

        TelemetrySection(
            live = live,
            connectionState = connectionState,
            units = units,
            onDetails = { showLiveDetails = true },
            onEndRide = onEndRide,
        )

        if (live.saveFailed.collectAsStateWithLifecycle().value) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Ride not saved", style = MaterialTheme.typography.titleMedium)
                    Text("The phone's storage is full or unavailable. Free some space, then retry. Keep RideBuddy open until it is saved.")
                    Button(onClick = onRetryRideSave, modifier = Modifier.align(Alignment.End)) { Text("Retry") }
                }
            }
        }

        Column {
            SectionHeader("Navigate")
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (guidance.active) {
                        ActiveGuidance(guidance, units, onStopNavigation, onOpenActiveNavigation)
                    } else {
                        OutlinedTextField(
                            value = destination,
                            onValueChange = { value ->
                                if (sharedDestination != null) onSharedDestinationHandled()
                                destination = value.take(MaxDestinationInputLength)
                            },
                            label = { Text("Google Maps link") },
                            placeholder = { Text("Share or paste a place from Maps") },
                            leadingIcon = { Icon(Icons.Outlined.Link, contentDescription = null) },
                            trailingIcon = if (destination.isNotBlank()) {
                                {
                                    IconButton(onClick = {
                                        destination = ""
                                        if (sharedDestination != null) onSharedDestinationHandled()
                                    }) { Icon(Icons.Outlined.Close, contentDescription = "Clear") }
                                }
                            } else null,
                            isError = sharedDestinationError != null,
                            supportingText = sharedDestinationError?.let { message -> { Text(message) } },
                            maxLines = 3,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(
                            onClick = {
                                if (sharedDestinationError != null) onSharedDestinationHandled()
                                onStartNavigation(destination)
                            },
                            enabled = destination.isNotBlank(),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Outlined.Directions, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                            Text("Start navigation")
                        }
                    }
                }
            }
        }

        Column {
            SectionHeader("Last ride")
            if (lastRide == null) {
                Text(
                    "Your rides will appear here once you set off.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                OutlinedCard(onClick = { onRideSelected(lastRide) }, modifier = Modifier.fillMaxWidth()) {
                    ListItem(
                        headlineContent = { Text(lastRide.routeLabel()) },
                        supportingContent = {
                            Text("${UnitFormatter.distance(lastRide.distanceKilometres, units, locale)} · " +
                                "${formatDuration(lastRide.durationMillis)} · ${UnitFormatter.speed(lastRide.averageSpeedKph, units, locale)} average")
                        },
                        leadingContent = { Icon(Icons.Outlined.Route, contentDescription = null) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }
        }
    }

    if (showLiveDetails) {
        ModalBottomSheet(
            onDismissRequest = { showLiveDetails = false },
            // Opens fully rather than at the half stop. The content is one scroll now, so a
            // partially expanded sheet would just be a drag standing between the rider and
            // the ride figures they opened it for.
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            LiveDetailsSheet(live = live, units = units)
        }
    }
    if (isNavigationStarting) {
        AlertDialog(
            onDismissRequest = onCancelNavigationStart,
            title = { Text("Finding route…") },
            text = { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() } },
            confirmButton = {},
            dismissButton = { TextButton(onClick = onCancelNavigationStart) { Text("Cancel") } },
        )
    }
}

/**
 * Reads the frame-rate streams as late as possible. Everything above it on the Live screen — the
 * connection card, the navigate card, the last-ride card — must not re-compose at 4 Hz, so the
 * telemetry read lives here rather than in [LiveScreen]'s own scope.
 */
@Composable
private fun TelemetrySection(
    live: LiveTelemetryStreams,
    connectionState: BikeConnectionState,
    units: DistanceUnits,
    onDetails: () -> Unit,
    onEndRide: () -> Unit,
) {
    if (connectionState !is BikeConnectionState.Connected) return
    val frame = live.telemetry.collectAsStateWithLifecycle().value ?: return
    val activeRide = live.activeRide.collectAsStateWithLifecycle().value
    TelemetryCard(frame, activeRide, units, onDetails, onEndRide)
}

/**
 * The bike's link, as one list item: what is happening, and the one thing to do about it.
 * Connected, it names the bike and offers Disconnect; otherwise it offers to connect, or shows
 * progress while a connection or pairing is under way.
 */
@Composable
internal fun ConnectionCard(
    state: BikeConnectionState,
    bikeAssociated: Boolean,
    pairingInProgress: Boolean,
    onConnectBike: () -> Unit,
    onDisconnectBike: () -> Unit,
) {
    val busy = state is BikeConnectionState.Connecting || state is BikeConnectionState.Authenticating || pairingInProgress
    val failed = state is BikeConnectionState.Failed
    // The retry count is deliberately not shown: a rider can simply wait for the link or a retry.
    val headline = when {
        state is BikeConnectionState.Connected -> state.deviceName
        busy && pairingInProgress && state !is BikeConnectionState.Connecting -> "Finding your bike…"
        busy -> "Connecting…"
        failed -> "Couldn't connect"
        bikeAssociated -> "Not connected"
        else -> "Not paired"
    }
    val supporting = when {
        state is BikeConnectionState.Connected -> "Connected"
        state is BikeConnectionState.Failed -> state.message.takeUnless { it == "Couldn't connect." }
        busy -> "Keep the ignition on"
        bikeAssociated -> "RideBuddy connects when the bike is nearby"
        else -> "Pair your motorcycle to see live data"
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = when {
            failed -> CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            )
            else -> CardDefaults.cardColors()
        },
    ) {
        ListItem(
            headlineContent = { Text(headline, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = supporting?.let { { Text(it) } },
            leadingContent = {
                // With a red brand, error and primary share a hue, so failure is also told by its icon.
                when {
                    failed -> Icon(Icons.Outlined.ErrorOutline, contentDescription = null)
                    state is BikeConnectionState.Connected ->
                        Icon(Icons.Outlined.BluetoothConnected, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    else -> Icon(Icons.Outlined.TwoWheeler, contentDescription = null)
                }
            },
            trailingContent = {
                when {
                    state is BikeConnectionState.Connected -> TextButton(onClick = onDisconnectBike) { Text("Disconnect") }
                    busy -> CircularProgressIndicator(Modifier.size(24.dp))
                    failed -> Button(onClick = onConnectBike) { Text("Retry") }
                    else -> FilledTonalButton(onClick = onConnectBike) { Text(if (bikeAssociated) "Connect" else "Find my bike") }
                }
            },
            colors = ListItemDefaults.colors(
                containerColor = Color.Transparent,
                headlineColor = LocalContentColor.current,
                supportingColor = LocalContentColor.current,
                leadingIconColor = LocalContentColor.current,
            ),
        )
    }
}

@Composable
private fun TelemetryCard(
    frame: TelemetryFrame,
    activeRide: ActiveRide?,
    units: DistanceUnits,
    onDetails: () -> Unit,
    onEndRide: () -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    val displayedSpeed = UnitFormatter.chartSpeed(frame.speedKilometresPerHour, units).roundToInt()
    val rpmFraction = (frame.engineRpm / RedlineRpm.toFloat()).coerceIn(0f, 1f)

    // One tap beside Details would otherwise split a ride in two with no way back.
    var confirmEnd by rememberSaveable { mutableStateOf(false) }
    if (confirmEnd) {
        AlertDialog(
            onDismissRequest = { confirmEnd = false },
            title = { Text("End this ride?") },
            text = { Text("It is saved to your history now. A new ride starts when you next set off.") },
            confirmButton = { TextButton(onClick = { confirmEnd = false; onEndRide() }) { Text("End ride") } },
            dismissButton = { TextButton(onClick = { confirmEnd = false }) { Text("Keep recording") } },
        )
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // Baseline alignment keeps the unit on the speed's baseline at any display scale.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                Text(
                    displayedSpeed.toString(),
                    style = MaterialTheme.typography.displayLarge,
                    modifier = Modifier.alignByBaseline(),
                )
                Text(
                    UnitFormatter.speedUnit(units),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.alignByBaseline().weight(1f),
                )
                activeRide?.let { ride ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(top = 8.dp)) {
                        Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.error, CircleShape))
                        Text(
                            "Recording · ${UnitFormatter.distance(ride.distanceKilometres, units, locale)}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Gauge(
                label = "RPM",
                value = "${frame.engineRpm}",
                fraction = rpmFraction,
                // Red is kept for the redline alone; the brand's primary is red too, so the normal
                // fill is secondary or the warning would not look like a change.
                color = if (rpmFraction > 0.85f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
                reading = "${frame.engineRpm} rpm of $RedlineRpm",
            )
            Gauge(
                label = "Throttle",
                value = "${frame.throttlePercent}%",
                fraction = frame.throttlePercent / 100f,
                color = MaterialTheme.colorScheme.secondary,
                reading = "${frame.throttlePercent} percent",
            )
            // Stacks rather than clips at large display scales.
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (activeRide != null) OutlinedButton(onClick = { confirmEnd = true }) { Text("End ride") }
                FilledTonalButton(onClick = onDetails) { Text("Details") }
            }
        }
    }
}

/**
 * A labelled bar: name left, value right, fill beneath. Revs and throttle are read as positions,
 * which a bar gives; only speed is worth reading as a number at riding pace.
 */
@Composable
private fun Gauge(
    label: String,
    value: String,
    fraction: Float,
    color: Color,
    reading: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleMedium)
        }
        // A gauge, not a progress bar: no gap and no end-stop dot.
        LinearProgressIndicator(
            progress = { fraction.coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(12.dp)
                .semantics { stateDescription = reading },
            color = color,
            gapSize = 0.dp,
            drawStopIndicator = {},
        )
    }
}

/** The current instruction, what is left of the route, and the two route actions. */
@Composable
private fun ActiveGuidance(
    guidance: GuidanceState,
    units: DistanceUnits,
    onStopNavigation: () -> Unit,
    onOpenActiveNavigation: () -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    val details = listOfNotNull(
        guidance.distanceToManeuverMetres?.let {
            stringResource(R.string.navigation_maneuver_distance, UnitFormatter.maneuverDistance(it, units, locale))
        },
        guidance.distanceToDestinationMetres?.let { "${UnitFormatter.distance(it / 1000.0, units, locale)} left" },
        guidance.timeToDestinationSeconds?.let { "ETA ${UnitFormatter.formatTime(System.currentTimeMillis() + it * 1000L)}" },
    )
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(Icons.Outlined.Directions, contentDescription = null, tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(32.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(guidance.instruction.ifBlank { "Navigating" }, style = MaterialTheme.typography.titleLarge)
            if (details.isNotEmpty()) {
                Text(details.joinToString(" · "), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    // FlowRow so the pair stacks rather than clips at large display scales.
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(onClick = onStopNavigation) { Text("End route") }
        Button(onClick = onOpenActiveNavigation) { Text(stringResource(R.string.navigation_full_map)) }
    }
}

/** "Home → Marina Beach", or the day it was ridden when the places are unknown. */
internal fun Ride.routeLabel(): String =
    if (startArea != null || endArea != null) "${startArea ?: "Start"} → ${endArea ?: "Parking location"}"
    else UnitFormatter.formatDate(startedAtMillis)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LiveDetailsSheet(
    live: LiveTelemetryStreams,
    units: DistanceUnits,
) {
    // Confined to the sheet: while it is open the rider is looking at live values, so following
    // the frame rate here is the point. Closing the sheet stops the work.
    val frame = live.telemetry.collectAsStateWithLifecycle().value
    val activeRide = live.activeRide.collectAsStateWithLifecycle().value
    val samples = live.rideSamples.collectAsStateWithLifecycle().value
    val metrics = live.rideMetrics.collectAsStateWithLifecycle().value
    val locale = LocalConfiguration.current.locales[0]
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 36.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Details", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })

        // One sheet rather than three levels. The levels asked the rider to choose how much
        // they wanted before they could see any of it, and the choice was sticky, so a rider
        // who had once picked Glance stopped being shown the ride figures at all. Scrolling
        // answers the same question without a decision in front of it.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Metric("Speed", frame?.let { UnitFormatter.speed(it.speedKilometresPerHour, units, locale) } ?: "—")
            Metric("RPM", frame?.engineRpm?.toString() ?: "—")
            Metric("Throttle", frame?.let { "${it.throttlePercent}%" } ?: "—")
        }
        DetailRow(
            "Mileage",
            frame?.let { UnitFormatter.mileage(it.instantaneousMileageKilometresPerLitre, units, locale) }
                ?: "— ${UnitFormatter.mileageUnit(units)}",
        )

        SectionHeader("This ride")
        if (activeRide == null) {
            Text("Recording starts when you set off.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            DetailRow("Distance", UnitFormatter.distance(activeRide.distanceKilometres, units, locale))
            DetailRow("Time", formatDuration(activeRide.lastSampleAtElapsedRealtime - activeRide.startedAtElapsedRealtime))
        }
        // Shown whether or not a ride is running: the detector works on the recent telemetry
        // window, not on the ride, so these are real counts even before recording starts.
        DetailRow("Hard acceleration", metrics.hardAccelerationEvents.toString())
        DetailRow("Hard braking", metrics.hardBrakingEvents.toString())

        SectionHeader("Ride data")
        val speedData = remember(samples, units) { telemetryChartData(samples, 120) { UnitFormatter.chartSpeed(it.speedKph, units) } }
        val rpmData = remember(samples) { telemetryChartData(samples, 120) { it.rpm.toDouble() } }
        val throttleData = remember(samples) { telemetryChartData(samples, 120) { it.throttlePercent.toDouble() } }
        LiveChart("Speed", speedData, UnitFormatter.speedUnit(units))
        LiveChart("RPM", rpmData, "rpm")
        LiveChart("Throttle", throttleData, "%")
    }
}

/**
 * Label left, value right — the same rhythm the live card's gauges use, so a figure is found
 * in the same place on both surfaces.
 */
@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun LiveChart(title: String, series: TelemetryChartData, unit: String) {
    val values = series.values
    val color = MaterialTheme.colorScheme.primary
    val locale = LocalConfiguration.current.locales[0]
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                // No space before a percent sign. The metrics higher up the sheet read "38%",
                // and one surface should not spell the same unit two ways; every other unit
                // here is a word and keeps its space.
                values.lastOrNull { it != null }?.let {
                    "Latest %.0f%s%s".format(locale, it, if (unit == "%") "" else " ", unit)
                } ?: "No data yet",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LineChart(
                values = values,
                timestampsMillis = series.timestampsMillis,
                height = 100.dp,
                topPadding = 8.dp,
                color = color,
                contentDescription = "$title over the last few minutes",
                strokeWidth = 3f,
                fillAlpha = 0.3f,
            )
            if (series.timestampsMillis.isNotEmpty()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(UnitFormatter.formatTime(series.timestampsMillis.first()), style = MaterialTheme.typography.labelSmall)
                    Text(UnitFormatter.formatTime(series.timestampsMillis.last()), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

/** Where the RS 457 tachometer turns red; the gauge fill and its warning colour key off this. */
private const val RedlineRpm = 10_500
