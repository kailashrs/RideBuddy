package com.spaceboy.ridebuddy.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.LocalGasStation
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material.icons.outlined.Eco
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.SportsMotorsports
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.spaceboy.ridebuddy.data.DistanceUnits
import com.spaceboy.ridebuddy.data.InsightPeriod
import com.spaceboy.ridebuddy.data.Ride
import com.spaceboy.ridebuddy.data.RideInsights
import com.spaceboy.ridebuddy.data.UnitFormatter
import java.util.Calendar

@Composable
fun InsightsScreen(
    modifier: Modifier = Modifier,
    insights: RideInsights,
    rides: List<Ride>,
    units: DistanceUnits,
    selectedPeriod: InsightPeriod,
    onPeriodSelected: (InsightPeriod) -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    val periodStart = when (selectedPeriod) {
        InsightPeriod.Today -> Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        else -> selectedPeriod.days?.let { System.currentTimeMillis() - it * 86_400_000L }
    }
    val periodRides = rides.filter { periodStart == null || it.startedAtMillis >= periodStart }
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Periods.forEach { (period, label) ->
                FilterChip(
                    selected = selectedPeriod == period,
                    onClick = { onPeriodSelected(period) },
                    label = {
                        Text(
                            text = label,
                            maxLines = 1,
                            softWrap = false,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Total distance", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    UnitFormatter.distance(insights.totalDistanceKilometres, units, locale),
                    style = MaterialTheme.typography.displaySmall,
                )
                insights.distanceChangePercent?.let {
                    Text(
                        "%+.0f%% from the previous period".format(locale, it),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (it >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        DistanceTrend(periodRides, units)

        MetricGrid(
            listOf(
                Triple("Rides", insights.rideCount.toString(), Icons.Outlined.Route),
                Triple("Ride time", formatDuration(insights.totalDurationMillis), Icons.Outlined.Timer),
                Triple("Fuel estimate", UnitFormatter.fuel(insights.estimatedFuelLitres, units, locale), Icons.Outlined.LocalGasStation),
                Triple("Avg ride", UnitFormatter.distance(insights.averageRideDistanceKilometres, units, locale), Icons.Outlined.Timeline),
                Triple("Avg duration", formatDuration(insights.averageRideDurationMillis), Icons.Outlined.Timer),
                Triple("Avg speed", UnitFormatter.speed(insights.averageSpeedKph, units, locale), Icons.Outlined.Speed),
                Triple("Avg RPM", "%.0f".format(locale, insights.averageRpm), Icons.Outlined.Settings),
                Triple("Avg throttle", "%.0f%%".format(locale, insights.averageThrottlePercent), Icons.Outlined.Sync),
                Triple("Mileage", UnitFormatter.consumption(insights.averageConsumptionLPer100Km, units, locale), Icons.Outlined.Eco),
                Triple("Longest ride", UnitFormatter.distance(insights.longestRideKilometres, units, locale), Icons.Outlined.EmojiEvents),
                Triple("Top speed", UnitFormatter.speed(insights.highestSpeedKph, units, locale), Icons.Outlined.SportsMotorsports),
            ),
        )
        if (insights.bestZeroToSixtyMillis != null || insights.bestZeroToHundredMillis != null) {
            Text(
                text = "Performance",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(start = 16.dp)
                    .semantics { heading() },
            )
            MetricGrid(
                listOfNotNull(
                    insights.bestZeroToSixtyMillis?.let { Triple("Best 0–60", "%.1f s".format(locale, it / 1_000.0), Icons.Outlined.Timer) },
                    insights.bestZeroToHundredMillis?.let { Triple("Best 0–100", "%.1f s".format(locale, it / 1_000.0), Icons.Outlined.Timer) },
                ),
            )
        }
        Text(
            "Fuel and mileage values are estimates derived from the bike's instantaneous telemetry.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DistanceTrend(rides: List<Ride>, units: DistanceUnits) {
    val values = rides.sortedBy(Ride::startedAtMillis).takeLast(14).map {
        if (units == DistanceUnits.Metric) it.distanceKilometres else it.distanceKilometres * 0.621371192
    }
    val hasData = values.any { it > 0.0 }
    val color = MaterialTheme.colorScheme.primary

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Recent distance trend", style = MaterialTheme.typography.titleMedium)
            Text(
                if (hasData) "Last ${values.size} rides" else "No ride data recorded in this period",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (hasData) {
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .height(100.dp)
                        .padding(top = 12.dp)
                        .semantics {
                            contentDescription = "Distance chart for the last ${values.size} rides"
                        },
                ) {
                    if (values.size < 2) return@Canvas
                    val maximum = values.maxOrNull()?.takeIf { it > 0.0 } ?: return@Canvas
                    val dx = size.width / (values.size - 1)
                    
                    val path = androidx.compose.ui.graphics.Path()
                    var previousX = 0f
                    var previousY = size.height - (values.first() / maximum * size.height).toFloat()
                    path.moveTo(previousX, previousY)
                    
                    for (i in 1 until values.size) {
                        val x = i * dx
                        val y = size.height - (values[i] / maximum * size.height).toFloat()
                        val controlX1 = previousX + (x - previousX) / 2f
                        val controlX2 = previousX + (x - previousX) / 2f
                        path.cubicTo(controlX1, previousY, controlX2, y, x, y)
                        previousX = x
                        previousY = y
                    }
                    
                    drawPath(path, color, style = androidx.compose.ui.graphics.drawscope.Stroke(3f))
                    
                    val fillPath = androidx.compose.ui.graphics.Path().apply {
                        addPath(path)
                        lineTo(size.width, size.height)
                        lineTo(0f, size.height)
                        close()
                    }
                    drawPath(
                        path = fillPath,
                        brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                            colors = listOf(color.copy(alpha = 0.4f), androidx.compose.ui.graphics.Color.Transparent),
                            startY = 0f,
                            endY = size.height
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun MetricGrid(metrics: List<Triple<String, String, ImageVector>>) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        metrics.chunked(2).forEach { row ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { (label, value, icon) ->
                    OutlinedCard(modifier = Modifier.weight(1f)) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            androidx.compose.material3.Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 8.dp))
                            Text(value, style = MaterialTheme.typography.titleLarge)
                            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

private val Periods = listOf(
    InsightPeriod.Today to "1D",
    InsightPeriod.SevenDays to "7D",
    InsightPeriod.ThirtyDays to "30D",
    InsightPeriod.NinetyDays to "90D",
    InsightPeriod.AllTime to "All",
)
