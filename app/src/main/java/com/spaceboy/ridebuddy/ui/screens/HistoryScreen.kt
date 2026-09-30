package com.spaceboy.ridebuddy.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.icu.text.DateIntervalFormat
import android.icu.util.DateInterval
import android.icu.util.TimeZone
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import com.spaceboy.ridebuddy.ui.components.EmptyState
import com.spaceboy.ridebuddy.ui.components.Metric
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
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
        EmptyState(
            icon = Icons.Outlined.Route,
            title = stringResource(R.string.history_empty),
            body = "RideBuddy records each ride automatically while the bike is connected.",
            modifier = modifier,
        )
        return
    }

    Column(modifier.fillMaxSize()) {
        // Filter chips, per Material: the presets are one tap, and the date chip opens the picker
        // and then carries the chosen range as its label.
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(HistoryFilter.All, HistoryFilter.ThisWeek, HistoryFilter.ThisMonth).forEach { option ->
                FilterChip(
                    selected = filter == option,
                    onClick = { selectFilter(option) },
                    label = { Text(historyFilterLabel(option, today, locale)) },
                )
            }
            val dates = filter as? HistoryFilter.Dates
            FilterChip(
                selected = dates != null,
                onClick = { choosingDates = true },
                label = { Text(if (dates != null) historyFilterLabel(dates, today, locale) else stringResource(R.string.history_choose_dates)) },
                leadingIcon = { Icon(Icons.Outlined.DateRange, contentDescription = null, Modifier.size(FilterChipDefaults.IconSize)) },
            )
        }
        val current = history
        when {
            current == null -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            current.rideCount == 0 -> EmptyState(
                icon = Icons.Outlined.DateRange,
                title = stringResource(R.string.history_empty_period),
                body = null,
                modifier = Modifier.weight(1f),
            )
            else -> {
                LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
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
                            Surface {
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
            Column {
                Text(
                    if (ride.startArea != null || ride.endArea != null) ride.routeLabel() else "Ride",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    android.text.format.DateFormat.getTimeFormat(LocalContext.current).format(Date(ride.startedAtMillis)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FlowRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Metric("Distance", valueStyle = MaterialTheme.typography.titleMedium, value = UnitFormatter.distance(ride.distanceKilometres, units, locale))
                Metric("Duration", valueStyle = MaterialTheme.typography.titleMedium, value = formatDuration(ride.durationMillis))
                Metric("Average", valueStyle = MaterialTheme.typography.titleMedium, value = UnitFormatter.speed(ride.averageSpeedKph, units, locale))
            }
            Text(
                "Top ${UnitFormatter.speed(ride.maximumSpeedKph, units, locale)} · ${UnitFormatter.fuel(ride.estimatedFuelLitres, units, locale)} fuel · ${UnitFormatter.mileage(ride.averageMileageKilometresPerLitre, units, locale)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Compact duration: minutes alone under an hour, hours and minutes above it. */
internal fun formatDuration(millis: Long): String {
    val minutes = millis.coerceAtLeast(0) / 60_000
    return if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else if (minutes == 0L && millis > 0L) "<1m" else "${minutes}m"
}
