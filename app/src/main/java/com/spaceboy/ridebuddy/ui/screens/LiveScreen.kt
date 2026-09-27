package com.spaceboy.ridebuddy.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.BluetoothSearching
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Directions
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.TwoWheeler
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
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
import com.spaceboy.ridebuddy.ui.theme.TelemetryHero
import com.spaceboy.ridebuddy.ui.theme.statusColors
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
) {
    val locale = LocalConfiguration.current.locales[0]
    // Deliberately unkeyed. A share arriving later is applied by the effect below; keying this on
    // sharedDestination instead would re-initialise the field when it goes back to null — which is
    // what clearing the field does — and wipe whatever the rider had typed.
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
            .padding(horizontal = 20.dp, vertical = 12.dp),
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

        val saveFailed = live.saveFailed.collectAsStateWithLifecycle().value
        if (saveFailed) {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Ride not saved", style = MaterialTheme.typography.titleMedium)
                    Text("Storage could not save the ride. Free some space, then retry. Keep RideBuddy open until it is saved.", style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = onRetryRideSave, modifier = Modifier.fillMaxWidth()) { Text("Retry save") }
                }
            }
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
                                text = guidance.instruction.ifBlank { "Navigating" },
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
                            Icon(Icons.Outlined.Close, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                            Text("End route")
                        }
                        Button(
                            onClick = onOpenActiveNavigation,
                            modifier = Modifier.weight(1f),
                            shape = MaterialTheme.shapes.large,
                        ) {
                            Icon(Icons.Outlined.Directions, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                            Text(stringResource(R.string.navigation_full_map))
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = destination,
                        onValueChange = { value ->
                            if (sharedDestinationError != null) onSharedDestinationHandled()
                            destination = value.take(MaxDestinationInputLength)
                        },
                        label = { Text("Google Maps link") },
                        placeholder = { Text("Paste a link from Google Maps") },
                        leadingIcon = { Icon(Icons.Outlined.Link, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
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
                        Icon(Icons.Outlined.Directions, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
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
                } ?: "Your rides will appear here once you set off.",
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
    }

    if (showLiveDetails) {
        ModalBottomSheet(
            onDismissRequest = { showLiveDetails = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            // Opens fully rather than at the half stop. The content is one scroll now, so a
            // partially expanded sheet would just be a drag standing between the rider and
            // the ride figures they opened it for.
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            LiveDetailsSheet(live = live, units = units)
        }
    }
    if (isNavigationStarting) {
        Dialog(onDismissRequest = onCancelNavigationStart) {
            Card {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator()
                        Spacer(Modifier.width(16.dp))
                        Text("Finding route…")
                    }
                    TextButton(
                        onClick = onCancelNavigationStart,
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        Text("Cancel")
                    }
                }
            }
        }
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

@Composable
internal fun ConnectionCard(
    state: BikeConnectionState,
    bikeAssociated: Boolean,
    pairingInProgress: Boolean,
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
                // The retry count is deliberately not shown. It is the app's own bookkeeping, and
                // a rider waiting for their bike can simply wait until the connection succeeds
                // or the app offers a retry.
                text = when (state) {
                    is BikeConnectionState.Connecting, is BikeConnectionState.Authenticating -> "Connecting…"
                    is BikeConnectionState.Failed -> "Couldn't connect"
                    else -> when {
                        pairingInProgress -> "Finding your bike…"
                        bikeAssociated -> "Not connected"
                        else -> "Not paired"
                    }
                },
                style = MaterialTheme.typography.headlineSmall,
            )
            val connectionHint = (state as? BikeConnectionState.Failed)
                ?.message
                ?.takeUnless { it == "Couldn't connect." }
            if (!connectionHint.isNullOrBlank()) {
                Text(
                    connectionHint,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
            if (state is BikeConnectionState.Connecting || state is BikeConnectionState.Authenticating || pairingInProgress) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                Button(onClick = onConnectBike) {
                    Icon(Icons.AutoMirrored.Outlined.BluetoothSearching, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text(if (bikeAssociated) "Connect" else "Find my bike")
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
    onEndRide: () -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    val displayedSpeed = UnitFormatter.chartSpeed(frame.speedKilometresPerHour, units).roundToInt()
    val rpmFraction = (frame.engineRpm / RedlineRpm.toFloat()).coerceIn(0f, 1f)

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Baseline alignment rather than a tuned bottom padding: the unit sits on the
                // speed's own baseline whatever the display scale does to either type size.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        displayedSpeed.toString(),
                        style = TelemetryHero,
                        modifier = Modifier.alignByBaseline(),
                    )
                    Text(
                        UnitFormatter.speedUnit(units),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.alignByBaseline(),
                    )
                }
                // Secondary rather than primary container. The app's seed colour is red, so in
                // the dark scheme primaryContainer and errorContainer are the same value: a
                // badge that is always on screen would wear the app's error colour, and would
                // shout louder than the redline the RPM gauge turns red for.
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Text(
                        text = "LIVE",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }

            Gauge(
                label = "RPM",
                value = "${frame.engineRpm} rpm",
                fraction = rpmFraction,
                color = if (rpmFraction > 0.85f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                reading = "${frame.engineRpm} rpm of $RedlineRpm",
            )
            Gauge(
                label = "Throttle",
                value = "${frame.throttlePercent}%",
                fraction = frame.throttlePercent / 100f,
                color = MaterialTheme.colorScheme.primary,
                reading = "${frame.throttlePercent} percent",
            )

            // Ride state reads as state, below the instruments and above the buttons, rather
            // than sharing a row with them where it looked like a third button.
            activeRide?.let { ride ->
                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(MaterialTheme.colorScheme.primary, CircleShape),
                        )
                        Text(
                            "Recording",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        UnitFormatter.distance(ride.distanceKilometres, units, locale),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }

            // Card actions: bottom, trailing edge, ordered by emphasis. Same order as the
            // navigate card's pair, so the action that ends something is always the left of
            // the two and no card trains a thumb to land on the other's "end" button.
            // FlowRow rather than Row because two buttons stop fitting on one line at large
            // display scales, where stacking them is better than clipping either label.
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (activeRide != null) {
                    OutlinedButton(onClick = onEndRide, shape = MaterialTheme.shapes.large) {
                        Text("End ride")
                    }
                }
                // Just "Details": the LIVE badge at the top of this card already says the
                // figures are live, and the button repeating it read as a second claim.
                FilledTonalButton(onClick = onDetails, shape = MaterialTheme.shapes.large) {
                    Text("Details")
                }
            }
        }
    }
}

/**
 * A labelled bar gauge: name on the left, current value on the right, fill beneath.
 *
 * Speed is the only figure worth reading as a number at riding pace; revs and throttle are
 * read as positions, which is what a bar gives. Both use the one shape so the pair reads as
 * a single instrument rather than two unrelated readouts.
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.labelMedium)
        }
        LinearProgressIndicator(
            progress = { fraction.coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(16.dp)
                // Without this it is announced as a bare progress bar with no value.
                .semantics { stateDescription = reading },
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            strokeCap = StrokeCap.Round,
        )
    }
}

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

        HorizontalDivider()
        SheetSection("This ride")
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

        HorizontalDivider()
        SheetSection("Ride data")
        val speedData = remember(samples, units) { telemetryChartData(samples, 120) { UnitFormatter.chartSpeed(it.speedKph, units) } }
        val rpmData = remember(samples) { telemetryChartData(samples, 120) { it.rpm.toDouble() } }
        val throttleData = remember(samples) { telemetryChartData(samples, 120) { it.throttlePercent.toDouble() } }
        LiveChart("Speed", speedData, UnitFormatter.speedUnit(units))
        LiveChart("RPM", rpmData, "rpm")
        LiveChart("Throttle", throttleData, "%")
    }
}

/**
 * A section heading inside the sheet, styled as the Live screen's own headings are.
 */
@Composable
private fun SheetSection(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.semantics { heading() },
    )
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
