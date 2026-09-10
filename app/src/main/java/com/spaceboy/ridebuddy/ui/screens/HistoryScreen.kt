package com.spaceboy.ridebuddy.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.spaceboy.ridebuddy.data.DistanceUnits
import com.spaceboy.ridebuddy.data.Ride
import com.spaceboy.ridebuddy.data.RideWeekSummary
import com.spaceboy.ridebuddy.data.UnitFormatter

/**
 * Ride history: a weekly summary, then every recorded ride newest first.
 *
 * Rides are recorded automatically, so this is the primary place a rider sees what the app
 * has captured. Tapping a row opens [com.spaceboy.ridebuddy.RideDetailActivity], which
 * loads the full sample series that this list deliberately does not.
 */
@Composable
fun HistoryScreen(
    modifier: Modifier = Modifier,
    rides: List<Ride>,
    weekSummary: RideWeekSummary,
    units: DistanceUnits,
    onRideSelected: (Ride) -> Unit,
) {
    if (rides.isEmpty()) {
        Column(
            modifier = modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(Icons.Outlined.Route, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(
                "Your rides will appear here",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(top = 20.dp),
            )
            Text(
                "Recording starts automatically after the connected bike begins moving.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { WeeklySummary(weekSummary, units) }
        items(rides, key = Ride::id) { ride -> RideCard(ride, units, onRideSelected) }
    }
}

/** One ride: where it went and the headline figures, with the route inside its details. */
@Composable
private fun RideCard(ride: Ride, units: DistanceUnits, onRideSelected: (Ride) -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    OutlinedCard(onClick = { onRideSelected(ride) }, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                UnitFormatter.formatShortDateTime(ride.startedAtMillis),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                if (ride.startArea != null || ride.endArea != null) {
                    "${ride.startArea ?: "Start"} → ${ride.endArea ?: "Parking location"}"
                } else "Recorded ride",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
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
private fun WeeklySummary(week: RideWeekSummary, units: DistanceUnits) {
    val locale = LocalConfiguration.current.locales[0]
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("This week", style = MaterialTheme.typography.titleLarge)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                RideValue("Distance", UnitFormatter.distance(week.distanceKilometres, units, locale))
                RideValue("Rides", week.rideCount.toString())
                RideValue("Avg duration", formatDuration(week.averageDurationMillis))
            }
            Text(
                "Average mileage ${UnitFormatter.mileage(week.mileageKilometresPerLitre, units, locale)}",
                style = MaterialTheme.typography.bodyMedium,
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
