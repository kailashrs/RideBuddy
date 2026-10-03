package com.spaceboy.ridebuddy.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Luggage
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.spaceboy.ridebuddy.R
import com.spaceboy.ridebuddy.data.DistanceUnits
import com.spaceboy.ridebuddy.data.HistoryFilter
import com.spaceboy.ridebuddy.data.InsightPeriod
import com.spaceboy.ridebuddy.data.InsightsCalculator
import com.spaceboy.ridebuddy.data.Ride
import com.spaceboy.ridebuddy.data.TripSummary
import com.spaceboy.ridebuddy.data.UnitFormatter
import com.spaceboy.ridebuddy.data.filterRideHistory
import com.spaceboy.ridebuddy.data.rides
import com.spaceboy.ridebuddy.data.sortedByLatestRide
import com.spaceboy.ridebuddy.ui.components.EmptyState
import com.spaceboy.ridebuddy.ui.components.Metric
import com.spaceboy.ridebuddy.ui.components.SectionHeader
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date

/** The Trips tab of History: one card per trip, and the button that starts a new one. */
@Composable
internal fun TripsTab(
    trips: List<TripSummary>,
    rides: List<Ride>,
    units: DistanceUnits,
    onTripSelected: (TripSummary) -> Unit,
    onSaveTrip: (id: Long?, name: String, rideIds: Set<Long>) -> Unit,
    modifier: Modifier = Modifier,
) {
    var creating by rememberSaveable { mutableStateOf(false) }
    if (creating) {
        TripEditorDialog(
            title = "New trip",
            initialName = "",
            initialRideIds = emptySet(),
            rides = rides,
            units = units,
            onDismiss = { creating = false },
            onSave = { name, rideIds ->
                creating = false
                onSaveTrip(null, name, rideIds)
            },
        )
    }
    Box(modifier.fillMaxSize()) {
        if (trips.isEmpty()) {
            EmptyState(
                icon = Icons.Outlined.Luggage,
                title = "No trips yet",
                body = "Group rides into a trip to see its distance, time and records together.",
            )
        } else {
            val sorted = remember(trips, rides) { trips.sortedByLatestRide(rides) }
            LazyColumn(
                Modifier.fillMaxSize(),
                // Clear of the extended FAB, so the last card can scroll out from under it.
                contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(sorted, key = { it.trip.id }) { trip ->
                    TripCard(trip, remember(trip, rides) { trip.rides(rides) }, units) { onTripSelected(trip) }
                }
            }
        }
        ExtendedFloatingActionButton(
            text = { Text("New trip") },
            icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
            onClick = { creating = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }
}

/** A trip's name, when it was ridden, and its headline totals, laid out like a ride's card. */
@Composable
private fun TripCard(trip: TripSummary, rides: List<Ride>, units: DistanceUnits, onClick: () -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    OutlinedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column {
                Text(trip.trip.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    tripDates(rides),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Metric("Distance", UnitFormatter.distance(rides.sumOf(Ride::distanceKilometres), units, locale),
                    valueStyle = MaterialTheme.typography.titleMedium)
                Metric("Ride time", formatDuration(rides.sumOf(Ride::durationMillis)), valueStyle = MaterialTheme.typography.titleMedium)
                Metric("Rides", rides.size.toString(), valueStyle = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

/** The calendar days a trip spans, from its first ride to its last. */
@Composable
internal fun tripDates(rides: List<Ride>): String {
    if (rides.isEmpty()) return "No rides"
    val zone = ZoneId.systemDefault()
    fun Ride.date(): LocalDate = Instant.ofEpochMilli(startedAtMillis).atZone(zone).toLocalDate()
    return dateRangeLabel(rides.first().date(), rides.last().date(), LocalDate.now(zone), LocalConfiguration.current.locales[0])
}

/**
 * A trip's figures: when it was ridden, the same totals, averages and records Insights shows
 * for a period, then the rides themselves.
 */
@Composable
internal fun TripDetailContent(
    rides: List<Ride>,
    units: DistanceUnits,
    onRideSelected: (Ride) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (rides.isEmpty()) {
        EmptyState(Icons.Outlined.Route, "No rides in this trip", "Edit the trip to add rides to it.", modifier)
        return
    }
    val insights = remember(rides) { InsightsCalculator.calculate(rides, InsightPeriod.AllTime) }
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            "${tripDates(rides)} · ${pluralStringResource(R.plurals.history_ride_count, rides.size, rides.size)}",
            Modifier.padding(horizontal = 16.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        InsightFigures(insights, units)
        // A trip runs over days and a ride card shows only its time, so the rides sit under the
        // same day headings History uses.
        val locale = LocalConfiguration.current.locales[0]
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionHeader("Rides")
            rides.groupBy { Instant.ofEpochMilli(it.startedAtMillis).atZone(zone).toLocalDate() }.forEach { (date, dayRides) ->
                Text(
                    historyDayLabel(date, today, locale),
                    Modifier.semantics { heading() },
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                dayRides.forEach { RideCard(it, units, onRideSelected) }
            }
        }
    }
}

/**
 * Names a trip and picks its rides: a full-screen dialog, as Material recommends for a task with
 * its own Save. Rides are grouped by day, and a day's checkbox takes or drops the whole day.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TripEditorDialog(
    title: String,
    initialName: String,
    initialRideIds: Set<Long>,
    rides: List<Ride>,
    units: DistanceUnits,
    onDismiss: () -> Unit,
    onSave: (name: String, rideIds: Set<Long>) -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val days = remember(rides, today, locale) { filterRideHistory(rides, HistoryFilter.All, today, zone, locale).days }
    var name by rememberSaveable { mutableStateOf(initialName) }
    var selected by rememberSaveable(stateSaver = RideIdsSaver) { mutableStateOf(initialRideIds) }
    val timeFormat = android.text.format.DateFormat.getTimeFormat(LocalContext.current)

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    title = { Text(title) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, contentDescription = "Close") }
                    },
                    actions = {
                        TextButton(
                            onClick = { onSave(name.trim(), selected) },
                            enabled = name.isNotBlank() && selected.isNotEmpty(),
                        ) { Text("Save") }
                    },
                )
            },
        ) { padding ->
            LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
                item(key = "name") {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Trip name") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                item(key = "count") {
                    Text(
                        "${pluralStringResource(R.plurals.history_ride_count, selected.size, selected.size)} selected",
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                days.forEach { day ->
                    item(key = "day:${day.date}") {
                        val ids = day.rides.map(Ride::id)
                        val state = when (ids.count { it in selected }) {
                            0 -> ToggleableState.Off
                            ids.size -> ToggleableState.On
                            else -> ToggleableState.Indeterminate
                        }
                        ListItem(
                            headlineContent = {
                                Text(
                                    historyDayLabel(day.date, today, locale),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            },
                            leadingContent = { TriStateCheckbox(state = state, onClick = null) },
                            modifier = Modifier
                                .semantics { heading() }
                                .triStateToggleable(state, role = Role.Checkbox) {
                                    selected = if (state == ToggleableState.On) selected - ids.toSet() else selected + ids
                                },
                        )
                    }
                    items(day.rides, key = { "ride:${it.id}" }) { ride ->
                        val checked = ride.id in selected
                        ListItem(
                            headlineContent = { Text(ride.title()) },
                            supportingContent = {
                                Text(
                                    "${timeFormat.format(Date(ride.startedAtMillis))} · " +
                                        "${UnitFormatter.distance(ride.distanceKilometres, units, locale)} · " +
                                        formatDuration(ride.durationMillis),
                                )
                            },
                            leadingContent = { Checkbox(checked = checked, onCheckedChange = null) },
                            modifier = Modifier.toggleable(checked, role = Role.Checkbox) { take ->
                                selected = if (take) selected + ride.id else selected - ride.id
                            },
                        )
                    }
                }
            }
        }
    }
}

private val RideIdsSaver = listSaver<Set<Long>, Long>(save = { it.toList() }, restore = { it.toSet() })
