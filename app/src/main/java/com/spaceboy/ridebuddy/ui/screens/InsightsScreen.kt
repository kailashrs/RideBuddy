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
                    fontWeight = FontWeight.Bold,
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
                "Rides" to insights.rideCount.toString(),
                "Ride time" to formatDuration(insights.totalDurationMillis),
                "Fuel estimate" to UnitFormatter.fuel(insights.estimatedFuelLitres, units, locale),
                "Avg ride" to UnitFormatter.distance(insights.averageRideDistanceKilometres, units, locale),
                "Avg duration" to formatDuration(insights.averageRideDurationMillis),
                "Avg speed" to UnitFormatter.speed(insights.averageSpeedKph, units, locale),
                "Avg RPM" to "%.0f".format(locale, insights.averageRpm),
                "Avg throttle" to "%.0f%%".format(locale, insights.averageThrottlePercent),
                "Mileage" to UnitFormatter.consumption(insights.averageConsumptionLPer100Km, units, locale),
                "Longest ride" to UnitFormatter.distance(insights.longestRideKilometres, units, locale),
                "Top speed" to UnitFormatter.speed(insights.highestSpeedKph, units, locale),
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
                    insights.bestZeroToSixtyMillis?.let { "Best 0–60" to "%.1f s".format(locale, it / 1_000.0) },
                    insights.bestZeroToHundredMillis?.let { "Best 0–100" to "%.1f s".format(locale, it / 1_000.0) },
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
            Text("Recent distance trend", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
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
                    val maximum = values.maxOrNull()?.takeIf { it > 0.0 } ?: return@Canvas
                    val gap = 4f
                    val width = (size.width - gap * (values.size - 1).coerceAtLeast(0)) / values.size.coerceAtLeast(1)
                    values.forEachIndexed { index, value ->
                        val top = size.height - (value / maximum * size.height).toFloat()
                        drawRect(color, topLeft = Offset(index * (width + gap), top), size = Size(width, size.height - top))
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricGrid(metrics: List<Pair<String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        metrics.chunked(2).forEach { row ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { (label, value) ->
                    OutlinedCard(modifier = Modifier.weight(1f)) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
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
