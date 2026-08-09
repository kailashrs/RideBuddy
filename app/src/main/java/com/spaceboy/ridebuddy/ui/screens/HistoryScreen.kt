package com.spaceboy.ridebuddy.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.spaceboy.ridebuddy.data.DistanceUnits
import com.spaceboy.ridebuddy.data.Ride
import com.spaceboy.ridebuddy.data.UnitFormatter
import java.util.Calendar

@Composable
fun HistoryScreen(
    modifier: Modifier = Modifier,
    rides: List<Ride>,
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
        item { WeeklySummary(rides, units) }
        items(rides, key = Ride::id) { ride -> RideCard(ride, units, onRideSelected) }
    }
}

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
                "Max ${UnitFormatter.speed(ride.maximumSpeedKph, units, locale)} • ${UnitFormatter.fuel(ride.estimatedFuelLitres, units, locale)} estimated fuel • ${UnitFormatter.consumption(ride.averageConsumptionLPer100Km, units, locale)} • ${ride.averageRpm} RPM",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun WeeklySummary(rides: List<Ride>, units: DistanceUnits) {
    val locale = LocalConfiguration.current.locales[0]
    val calendar = Calendar.getInstance().apply {
        set(Calendar.DAY_OF_WEEK, firstDayOfWeek)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    val week = rides.filter { it.startedAtMillis >= calendar.timeInMillis }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("This week", style = MaterialTheme.typography.titleLarge)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                RideValue("Distance", UnitFormatter.distance(week.sumOf(Ride::distanceKilometres), units, locale))
                RideValue("Rides", week.size.toString())
                RideValue("Avg duration", formatDuration(week.map(Ride::durationMillis).average().takeIf(Double::isFinite)?.toLong() ?: 0L))
            }
            Text(
                "Average mileage ${week.takeIf { it.isNotEmpty() }?.map(Ride::averageConsumptionLPer100Km)?.average()?.let { UnitFormatter.consumption(it, units, locale) } ?: "—"}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RoutePreview(ride: Ride) {
    val points = ride.routePreview
    val color = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outlineVariant
    val surfaceColor = MaterialTheme.colorScheme.surface
    val cachedPath = remember(points) { Path() }
    Canvas(Modifier.fillMaxWidth().height(100.dp).padding(vertical = 8.dp).semantics { contentDescription = "Route preview from ${ride.startArea ?: "start"} to ${ride.endArea ?: "parking location"}" }) {
        val minLat = points.minOf { it.latitude }
        val maxLat = points.maxOf { it.latitude }
        val minLon = points.minOf { it.longitude }
        val maxLon = points.maxOf { it.longitude }
        val latRange = (maxLat - minLat).takeIf { it > 0.0 } ?: 1.0
        val lonRange = (maxLon - minLon).takeIf { it > 0.0 } ?: 1.0
        
        // Draw grid
        repeat(4) { index ->
            val y = size.height * index / 3f
            drawLine(outline, Offset(0f, y), Offset(size.width, y), 1f)
        }
        
        if (points.size < 2) return@Canvas
        
        cachedPath.reset()
        
        // Calculate all points first
        val mappedPoints = points.map { point ->
            val x = ((point.longitude - minLon) / lonRange * size.width).toFloat()
            val y = (size.height - (point.latitude - minLat) / latRange * size.height).toFloat()
            Offset(x, y)
        }
        
        // Draw path using bezier curves
        var previous = mappedPoints.first()
        cachedPath.moveTo(previous.x, previous.y)
        
        for (i in 1 until mappedPoints.size) {
            val current = mappedPoints[i]
            val controlX = previous.x + (current.x - previous.x) / 2f
            cachedPath.cubicTo(controlX, previous.y, controlX, current.y, current.x, current.y)
            previous = current
        }
        
        drawPath(cachedPath, color, style = Stroke(5f))
        
        // Draw start point
        val startPoint = mappedPoints.first()
        drawCircle(color, radius = 6f, center = startPoint)
        
        // Draw end point
        val endPoint = mappedPoints.last()
        drawCircle(surfaceColor, radius = 6f, center = endPoint)
        drawCircle(color, radius = 6f, center = endPoint, style = Stroke(3f))
    }
}

@Composable
private fun RideValue(label: String, value: String) {
    Column {
        Text(value, style = MaterialTheme.typography.titleMedium)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

internal fun formatDuration(millis: Long): String {
    val minutes = millis / 60_000
    return if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"
}
