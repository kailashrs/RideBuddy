package com.spaceboy.ridebuddy.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.selected
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.spaceboy.ridebuddy.data.DistanceUnits
import com.spaceboy.ridebuddy.data.InsightPeriod
import com.spaceboy.ridebuddy.data.RideInsights
import com.spaceboy.ridebuddy.data.UnitFormatter
import com.spaceboy.ridebuddy.ui.components.LineChart
import com.spaceboy.ridebuddy.ui.components.Metric

@OptIn(ExperimentalMaterial3Api::class)
/**
 * Aggregate riding statistics over a selectable period: totals, averages, records, and a
 * distance trend. All computed by [com.spaceboy.ridebuddy.data.InsightsCalculator] from
 * stored history; this screen only presents them.
 */
@Composable
fun InsightsScreen(
    modifier: Modifier = Modifier,
    insights: RideInsights,
    units: DistanceUnits,
    selectedPeriod: InsightPeriod,
    onPeriodSelected: (InsightPeriod) -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PeriodSelector(selectedPeriod, onPeriodSelected)
        if (insights.rideCount == 0) {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Outlined.Route, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text(if (selectedPeriod == InsightPeriod.AllTime) "Your rides will appear here" else "No rides in this period",
                        style = MaterialTheme.typography.bodyLarge)
                }
            }
            return@Column
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Total distance", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    UnitFormatter.distance(insights.totalDistanceKilometres, units, locale),
                    style = MaterialTheme.typography.displaySmall,
                )
                insights.distanceChangePercent?.let {
                    Text(
                        "%+.0f%% from the previous period".format(locale, it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        DistanceTrend(insights.distanceTrendKilometres, units)

        MetricGrid(
            listOf(
                InsightMetric("Rides", insights.rideCount.toString(), Icons.Outlined.Route),
                InsightMetric("Ride time", formatDuration(insights.totalDurationMillis), Icons.Outlined.Timer),
                InsightMetric("Fuel estimate", UnitFormatter.fuel(insights.estimatedFuelLitres, units, locale), Icons.Outlined.LocalGasStation),
                InsightMetric("Avg ride", UnitFormatter.distance(insights.averageRideDistanceKilometres, units, locale), Icons.Outlined.Timeline),
                InsightMetric("Avg duration", formatDuration(insights.averageRideDurationMillis), Icons.Outlined.Timer),
                InsightMetric("Avg speed", UnitFormatter.speed(insights.averageSpeedKph, units, locale), Icons.Outlined.Speed),
                InsightMetric("Avg RPM", "%.0f".format(locale, insights.averageRpm), Icons.Outlined.Settings),
                InsightMetric("Avg throttle", "%.0f%%".format(locale, insights.averageThrottlePercent), Icons.Outlined.Sync),
                InsightMetric("Mileage", UnitFormatter.mileage(insights.averageMileageKilometresPerLitre, units, locale), Icons.Outlined.Eco),
                InsightMetric("Longest ride", UnitFormatter.distance(insights.longestRideKilometres, units, locale), Icons.Outlined.EmojiEvents),
                InsightMetric("Top speed", UnitFormatter.speed(insights.highestSpeedKph, units, locale), Icons.Outlined.SportsMotorsports),
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
                    insights.bestZeroToSixtyMillis?.let { InsightMetric("Best 0–60 km/h", "%.1f s".format(locale, it / 1_000.0), Icons.Outlined.Timer) },
                    insights.bestZeroToHundredMillis?.let { InsightMetric("Best 0–100 km/h", "%.1f s".format(locale, it / 1_000.0), Icons.Outlined.Timer) },
                ),
            )
        }
        Text(
            "Fuel and mileage are estimates.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Distance per ride across the period, zero-based so ride lengths are compared against zero
 * rather than against each other — an auto-ranged axis would make a set of similar rides
 * look wildly variable.
 */
@Composable
private fun DistanceTrend(distancesKilometres: List<Double>, units: DistanceUnits) {
    val values = remember(distancesKilometres, units) {
        distancesKilometres.map { UnitFormatter.distanceValue(it, units) }
    }
    val hasData = values.any { it > 0.0 }
    val color = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val locale = LocalConfiguration.current.locales[0]

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Distance per ride", style = MaterialTheme.typography.titleMedium)
            Text(
                if (hasData) "${values.size} ${if (values.size == 1) "ride" else "rides"} · oldest to newest" else "No ride data recorded in this period",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (hasData) {
                LineChart(
                    values = values,
                    height = 120.dp,
                    topPadding = 12.dp,
                    color = color,
                    contentDescription = "Distance for the last ${values.size} rides, oldest to newest",
                    strokeWidth = 3f,
                    fillAlpha = 0.4f,
                    drawBaseline = true,
                    baselineColor = grid,
                )
                // A single ride has no trend to read off the line, so the figures stay in text.
                FlowRow(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Longest ${UnitFormatter.distance(distancesKilometres.max(), units, locale)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Latest ${UnitFormatter.distance(distancesKilometres.last(), units, locale)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Immutable
private data class InsightMetric(val label: String, val value: String, val icon: ImageVector)

/** Two equal columns, with content-driven height so larger text can wrap. */
@Composable
private fun MetricGrid(metrics: List<InsightMetric>) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        metrics.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { (label, value, icon) ->
                    OutlinedCard(Modifier.weight(1f).fillMaxHeight()) {
                        Column(Modifier.padding(12.dp)) {
                            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(bottom = 8.dp))
                            Metric(label, value)
                        }
                    }
                }
                if (row.size < 2) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PeriodSelector(selectedPeriod: InsightPeriod, onSelected: (InsightPeriod) -> Unit) {
    val fontScale = LocalDensity.current.fontScale
    var expanded by remember { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < 360.dp || fontScale > 1.1f) {
            Box {
                OutlinedButton(onClick = { expanded = true }) {
                    Text(selectedPeriod.periodLabel(), Modifier.padding(end = 8.dp))
                    Icon(Icons.Outlined.ArrowDropDown, contentDescription = null)
                }
                DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                    Periods.forEach { (period, _) ->
                        DropdownMenuItem(
                            text = { Text(period.periodLabel()) },
                            modifier = Modifier.semantics { selected = period == selectedPeriod },
                            onClick = { onSelected(period); expanded = false },
                        )
                    }
                }
            }
        } else {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                Periods.forEachIndexed { index, (period, label) ->
                    SegmentedButton(
                        selected = period == selectedPeriod,
                        onClick = { onSelected(period) },
                        shape = SegmentedButtonDefaults.itemShape(index, Periods.size),
                        modifier = Modifier.semantics { contentDescription = period.periodLabel() },
                        label = { Text(label, maxLines = 1, softWrap = false) },
                    )
                }
            }
        }
    }
}

private fun InsightPeriod.periodLabel(): String = when (this) {
    InsightPeriod.OneDay -> "Last 24 hours"
    InsightPeriod.SevenDays -> "Last 7 days"
    InsightPeriod.ThirtyDays -> "Last 30 days"
    InsightPeriod.NinetyDays -> "Last 90 days"
    InsightPeriod.AllTime -> "All time"
}

/** Period selector, with the short labels the segmented buttons show. */
private val Periods = listOf(
    InsightPeriod.OneDay to "1D",
    InsightPeriod.SevenDays to "7D",
    InsightPeriod.ThirtyDays to "30D",
    InsightPeriod.NinetyDays to "90D",
    InsightPeriod.AllTime to "All",
)
