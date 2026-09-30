package com.spaceboy.ridebuddy.ui.screens

import android.icu.text.DateIntervalFormat
import android.icu.util.DateInterval
import android.icu.util.TimeZone
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.spaceboy.ridebuddy.R
import com.spaceboy.ridebuddy.data.DistanceUnits
import com.spaceboy.ridebuddy.data.HistoryFilter
import com.spaceboy.ridebuddy.data.Ride
import com.spaceboy.ridebuddy.data.RideHistory
import com.spaceboy.ridebuddy.data.UnitFormatter
import com.spaceboy.ridebuddy.data.dateRange
import com.spaceboy.ridebuddy.data.filterRideHistory
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Filter state is saved by the existing per-tab SaveableStateProvider. */
@Composable
fun HistoryScreen(
    modifier: Modifier = Modifier,
    rides: List<Ride>,
    units: DistanceUnits,
    onRideSelected: (Ride) -> Unit,
    clock: Clock? = null,
) {
    val locale = LocalConfiguration.current.locales[0]
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var calendar by remember(clock) { mutableStateOf(ZonedDateTime.now(clock ?: Clock.systemDefaultZone())) }
    // Refresh on return and at midnight, only while this screen is visible.
    LaunchedEffect(lifecycle, clock) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                val now = ZonedDateTime.now(clock ?: Clock.systemDefaultZone())
                calendar = now
                delay(Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay(now.zone))
                    .toMillis().coerceAtLeast(1L))
            }
        }
    }
    val today = calendar.toLocalDate()
    val zone = calendar.zone
    var filter by rememberSaveable(stateSaver = HistoryFilterSaver) { mutableStateOf<HistoryFilter>(HistoryFilter.All) }
    var menuOpen by remember { mutableStateOf(false) }
    var choosingDates by rememberSaveable { mutableStateOf(false) }
    var history by remember(rides, filter, today, zone, locale) { mutableStateOf<RideHistory?>(null) }
    LaunchedEffect(rides, filter, today, zone, locale) {
        history = withContext(Dispatchers.Default) { filterRideHistory(rides, filter, today, zone, locale) }
    }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val selectFilter: (HistoryFilter) -> Unit = { selection ->
        filter = selection
        scope.launch { listState.scrollToItem(0) }
    }

    if (choosingDates) {
        HistoryDateRangeDialog(
            initialRange = filter.dateRange(today, locale),
            today = today,
            onDismiss = { choosingDates = false },
            onApply = { selectFilter(it); choosingDates = false },
        )
    }
    if (rides.isEmpty()) {
        Column(modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            Icon(Icons.Outlined.Route, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.history_empty), style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(top = 20.dp))
        }
        return
    }

    Column(modifier.fillMaxSize()) {
        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box {
                OutlinedButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Outlined.DateRange, contentDescription = null)
                    Text(historyFilterLabel(filter, today, locale),
                        modifier = Modifier.padding(horizontal = 8.dp).weight(1f, fill = false),
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Icon(Icons.Outlined.ArrowDropDown, contentDescription = null)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    listOf(HistoryFilter.All, HistoryFilter.ThisWeek, HistoryFilter.ThisMonth).forEach { option ->
                        DropdownMenuItem(
                            text = { Text(historyFilterLabel(option, today, locale)) },
                            onClick = { selectFilter(option); menuOpen = false },
                            modifier = Modifier.semantics { selected = filter == option },
                            trailingIcon = if (filter == option) {{ Icon(Icons.Outlined.Check, contentDescription = null) }} else null,
                        )
                    }
                    DropdownMenuItem(text = { Text(stringResource(R.string.history_choose_dates)) },
                        onClick = { menuOpen = false; choosingDates = true },
                        modifier = Modifier.semantics { selected = filter is HistoryFilter.Dates })
                }
            }
            if (filter != HistoryFilter.All) {
                TextButton(onClick = { selectFilter(HistoryFilter.All) }) { Text(stringResource(R.string.history_clear_filter)) }
            }
        }
        val current = history
        when {
            current == null -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            current.rideCount == 0 -> Column(
                Modifier.fillMaxWidth().weight(1f).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(stringResource(R.string.history_empty_period), style = MaterialTheme.typography.bodyLarge)
            }
            else -> {
                LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item(key = "summary", contentType = "summary") {
                        Text(
                            "${pluralStringResource(R.plurals.history_ride_count, current.rideCount, current.rideCount)} · " +
                                "${UnitFormatter.distance(current.distanceKilometres, units, locale)} · ${formatDuration(current.durationMillis)}",
                            Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    current.days.forEach { day ->
                        stickyHeader(key = "day:${day.date}") {
                            Surface(color = MaterialTheme.colorScheme.background) {
                                Text(historyDayLabel(day.date, today, locale),
                                    Modifier.fillMaxWidth().padding(vertical = 12.dp).semantics { heading() },
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        items(day.rides, key = { "ride:${it.id}" }, contentType = { "ride" }) {
                            RideCard(it, units, onRideSelected)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun historyDayLabel(date: LocalDate, today: LocalDate, locale: Locale): String = when (date) {
    today -> stringResource(R.string.history_today)
    today.minusDays(1) -> stringResource(R.string.history_yesterday)
    else -> date.format(DateTimeFormatter.ofPattern(
        android.text.format.DateFormat.getBestDateTimePattern(locale, if (date.year == today.year) "EEEMMMd" else "EEEyMMMd"), locale))
}

@Composable
private fun historyFilterLabel(filter: HistoryFilter, today: LocalDate, locale: Locale): String = when (filter) {
    HistoryFilter.All -> stringResource(R.string.history_all_rides)
    HistoryFilter.ThisWeek -> stringResource(R.string.history_this_week)
    HistoryFilter.ThisMonth -> stringResource(R.string.history_this_month)
    is HistoryFilter.Dates -> remember(filter, today.year, locale) {
        // The interval is made from calendar dates, so format it in UTC without shifting a day.
        val formatter = DateIntervalFormat.getInstance(
            if (filter.start.year == today.year && filter.endInclusive.year == today.year) "MMMd" else "yMMMd", locale)
        formatter.timeZone = TimeZone.getTimeZone("UTC")
        formatter.format(DateInterval(filter.start.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            filter.endInclusive.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()))
    }
}

private val HistoryFilterSaver = listSaver<HistoryFilter, String>(
    save = { when (it) {
        HistoryFilter.All -> listOf("all")
        HistoryFilter.ThisWeek -> listOf("week")
        HistoryFilter.ThisMonth -> listOf("month")
        is HistoryFilter.Dates -> listOf("dates", it.start.toString(), it.endInclusive.toString())
    } },
    restore = { saved -> runCatching {
        when (saved.first()) {
            "week" -> HistoryFilter.ThisWeek
            "month" -> HistoryFilter.ThisMonth
            "dates" -> HistoryFilter.Dates(LocalDate.parse(saved[1]), LocalDate.parse(saved[2]))
            else -> HistoryFilter.All
        }
    }.getOrDefault(HistoryFilter.All) },
)

/** One ride: where it went and the headline figures, with the route inside its details. */
@Composable
private fun RideCard(ride: Ride, units: DistanceUnits, onRideSelected: (Ride) -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    OutlinedCard(onClick = { onRideSelected(ride) }, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                android.text.format.DateFormat.getTimeFormat(LocalContext.current).format(Date(ride.startedAtMillis)),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                if (ride.startArea != null || ride.endArea != null) {
                    "${ride.startArea ?: "Start"} → ${ride.endArea ?: "Parking location"}"
                } else "Recorded ride",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RideValue("Distance", UnitFormatter.distance(ride.distanceKilometres, units, locale))
                RideValue("Duration", formatDuration(ride.durationMillis))
                RideValue("Average", UnitFormatter.speed(ride.averageSpeedKph, units, locale))
            }
            Text(
                "Max ${UnitFormatter.speed(ride.maximumSpeedKph, units, locale)} • ${UnitFormatter.fuel(ride.estimatedFuelLitres, units, locale)} estimated fuel • ${UnitFormatter.mileage(ride.averageMileageKilometresPerLitre, units, locale)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RideValue(label: String, value: String) {
    Column {
        Text(value, style = MaterialTheme.typography.titleMedium)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Compact duration: minutes alone under an hour, hours and minutes above it. */
internal fun formatDuration(millis: Long): String {
    val minutes = millis.coerceAtLeast(0) / 60_000
    return if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else if (minutes == 0L && millis > 0L) "<1m" else "${minutes}m"
}
