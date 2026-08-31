package com.spaceboy.ridebuddy.ui.screens

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
import com.spaceboy.ridebuddy.ui.components.LineChartScalePolicy
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
        // Exactly one period is ever selected, which is what a segmented button says and a
        // filter chip does not. It also gives equal-width segments without the weight() hack.
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            Periods.forEachIndexed { index, (period, label) ->
                SegmentedButton(
                    selected = selectedPeriod == period,
                    onClick = { onPeriodSelected(period) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = Periods.size),
                    label = {
                        Text(
                            text = label,
                            maxLines = 1,
                            softWrap = false,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                )
            }
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
                        color = if (it >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
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
                    insights.bestZeroToSixtyMillis?.let { InsightMetric("Best 0–60", "%.1f s".format(locale, it / 1_000.0), Icons.Outlined.Timer) },
                    insights.bestZeroToHundredMillis?.let { InsightMetric("Best 0–100", "%.1f s".format(locale, it / 1_000.0), Icons.Outlined.Timer) },
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
                LineChart(
                    values = values,
                    height = 100.dp,
                    topPadding = 12.dp,
                    color = color,
                    contentDescription = "Distance chart for the last ${values.size} rides",
                    scalePolicy = LineChartScalePolicy.ZeroBased,
                    clampNegativeValues = false,
                    smooth = true,
                    strokeWidth = 3f,
                    fillAlpha = 0.4f,
                )
            }
        }
    }
}

@Immutable
private data class InsightMetric(val label: String, val value: String, val icon: ImageVector)

/** Two-column grid. Plain rows rather than a lazy grid: the list is short and fixed. */
@Composable
private fun MetricGrid(metrics: List<InsightMetric>) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        metrics.chunked(2).forEach { row ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { (label, value, icon) ->
                    OutlinedCard(modifier = Modifier.weight(1f)) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 8.dp))
                            Metric(label = label, value = value)
                        }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** Period selector, with the short labels the segmented buttons show. */
private val Periods = listOf(
    InsightPeriod.Today to "1D",
    InsightPeriod.SevenDays to "7D",
    InsightPeriod.ThirtyDays to "30D",
    InsightPeriod.NinetyDays to "90D",
    InsightPeriod.AllTime to "All",
)
