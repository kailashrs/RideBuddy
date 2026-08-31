package com.spaceboy.ridebuddy.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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

/** One ride: its route sketch, where it went, and the headline figures. */
@Composable
private fun RideCard(ride: Ride, units: DistanceUnits, onRideSelected: (Ride) -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    OutlinedCard(onClick = { onRideSelected(ride) }, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                UnitFormatter.formatShortDateTime(ride.startedAtMillis),
                style = MaterialTheme.typography.titleMedium,
            )
            ride.startArea?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (ride.routePreview.size > 1) RoutePreview(ride)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                RideValue("Distance", UnitFormatter.distance(ride.distanceKilometres, units, locale))
                RideValue("Duration", formatDuration(ride.durationMillis))
                RideValue("Average", UnitFormatter.speed(ride.averageSpeedKph, units, locale))
            }
            Text(
                "Max ${UnitFormatter.speed(ride.maximumSpeedKph, units, locale)} • ${UnitFormatter.fuel(ride.estimatedFuelLitres, units, locale)} estimated fuel • ${UnitFormatter.mileage(ride.averageMileageKilometresPerLitre, units, locale)} • ${ride.averageRpm} RPM",
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

/**
 * A route sketch drawn from the stored preview points, with no map involved.
 *
 * A map tile per history row would be slow, need a network, and cost quota; the shape of
 * the ride is all a list row needs. Points are normalised into the card's own bounds — so
 * every route fills the space regardless of its real extent — and the latitude axis is
 * flipped, since latitude increases upward while screen coordinates increase downward.
 *
 * Normalisation is remembered against the points and the drawing is built in
 * `drawWithCache`, so scrolling neither recomputes the projection nor rebuilds the path.
 * The start dot is filled and the end dot hollow, which distinguishes direction without a
 * legend.
 */
@Composable
private fun RoutePreview(ride: Ride) {
    val points = ride.routePreview
    val color = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outlineVariant
    val surfaceColor = MaterialTheme.colorScheme.surface
    val normalizedPoints = remember(points) {
        val minLat = points.minOf { it.latitude }
        val maxLat = points.maxOf { it.latitude }
        val minLon = points.minOf { it.longitude }
        val maxLon = points.maxOf { it.longitude }
        val latRange = (maxLat - minLat).takeIf { it > 0.0 } ?: 1.0
        val lonRange = (maxLon - minLon).takeIf { it > 0.0 } ?: 1.0
        points.map { point ->
            Offset(
                x = ((point.longitude - minLon) / lonRange).toFloat(),
                y = (1.0 - (point.latitude - minLat) / latRange).toFloat(),
            )
        }
    }
    Spacer(
        Modifier
            .fillMaxWidth()
            .height(100.dp)
            .padding(vertical = 8.dp)
            .semantics {
                contentDescription = "Route preview from ${ride.startArea ?: "start"} to ${ride.endArea ?: "parking location"}"
            }
            .drawWithCache {
                val mappedPoints = normalizedPoints.map { point ->
                    Offset(point.x * size.width, point.y * size.height)
                }
                val path = Path().apply {
                    var previous = mappedPoints.first()
                    moveTo(previous.x, previous.y)
                    for (index in 1..mappedPoints.lastIndex) {
                        val current = mappedPoints[index]
                        val controlX = previous.x + (current.x - previous.x) / 2f
                        cubicTo(controlX, previous.y, controlX, current.y, current.x, current.y)
                        previous = current
                    }
                }
                val startPoint = mappedPoints.first()
                val endPoint = mappedPoints.last()
                onDrawBehind {
                    repeat(4) { index ->
                        val y = size.height * index / 3f
                        drawLine(outline, Offset(0f, y), Offset(size.width, y), 1f)
                    }
                    drawPath(path, color, style = Stroke(5f))
                    drawCircle(color, radius = 6f, center = startPoint)
                    drawCircle(surfaceColor, radius = 6f, center = endPoint)
                    drawCircle(color, radius = 6f, center = endPoint, style = Stroke(3f))
                }
            },
    )
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
    val minutes = millis / 60_000
    return if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"
}
