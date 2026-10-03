package com.spaceboy.ridebuddy.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Eco
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.LocalGasStation
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.SportsMotorsports
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.spaceboy.ridebuddy.data.DistanceUnits
import com.spaceboy.ridebuddy.data.InsightPeriod
import com.spaceboy.ridebuddy.data.RideInsights
import com.spaceboy.ridebuddy.data.UnitFormatter
import com.spaceboy.ridebuddy.ui.components.EmptyState
import com.spaceboy.ridebuddy.ui.components.LineChart
import com.spaceboy.ridebuddy.ui.components.Metric
import com.spaceboy.ridebuddy.ui.components.SectionHeader


/**
 * Aggregate riding statistics over a selectable period: the total, a distance trend, then the
 * figures grouped as totals, averages and records. All computed by
 * [com.spaceboy.ridebuddy.data.InsightsCalculator]; this screen only presents them.
 */
@Composable
fun InsightsScreen(
    modifier: Modifier = Modifier,
    insights: RideInsights,
    units: DistanceUnits,
    selectedPeriod: InsightPeriod,
    onPeriodSelected: (InsightPeriod) -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PeriodSelector(selectedPeriod, onPeriodSelected)
        if (insights.rideCount == 0) {
            EmptyState(
                icon = Icons.Outlined.Route,
                title = if (selectedPeriod == InsightPeriod.AllTime) "Your rides will appear here" else "No rides in this period",
                body = null,
                modifier = Modifier.heightIn(min = 320.dp).padding(horizontal = 16.dp),
            )
            return@Column
        }
        InsightFigures(insights, units)
    }
}

/** The period's total, its distance trend, then totals, averages and records. */
@Composable
internal fun InsightFigures(insights: RideInsights, units: DistanceUnits) {
    val locale = LocalConfiguration.current.locales[0]
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Total distance", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(UnitFormatter.distance(insights.totalDistanceKilometres, units, locale), style = MaterialTheme.typography.displaySmall)
                insights.distanceChangePercent?.let {
                    Text(
                        "%+.0f%% from the previous period".format(locale, it),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        DistanceTrend(insights.distanceTrendKilometres, units)

        MetricSection(
            "Totals",
            listOf(
                InsightMetric("Rides", insights.rideCount.toString(), Icons.Outlined.Route),
                InsightMetric("Ride time", formatDuration(insights.totalDurationMillis), Icons.Outlined.Timer),
                InsightMetric("Fuel (est.)", UnitFormatter.fuel(insights.estimatedFuelLitres, units, locale), Icons.Outlined.LocalGasStation),
                InsightMetric("Mileage (est.)", UnitFormatter.mileage(insights.averageMileageKilometresPerLitre, units, locale), Icons.Outlined.Eco),
            ),
        )
        MetricSection(
            "Averages",
            listOf(
                InsightMetric("Distance", UnitFormatter.distance(insights.averageRideDistanceKilometres, units, locale), Icons.Outlined.Timeline),
                InsightMetric("Duration", formatDuration(insights.averageRideDurationMillis), Icons.Outlined.Timer),
                InsightMetric("Speed", UnitFormatter.speed(insights.averageSpeedKph, units, locale), Icons.Outlined.Speed),
                InsightMetric("RPM", "%.0f".format(locale, insights.averageRpm), Icons.Outlined.Settings),
                InsightMetric("Throttle", "%.0f%%".format(locale, insights.averageThrottlePercent), Icons.Outlined.Sync),
            ),
        )
        MetricSection(
            "Records",
            listOfNotNull(
                InsightMetric("Longest ride", UnitFormatter.distance(insights.longestRideKilometres, units, locale), Icons.Outlined.EmojiEvents),
                InsightMetric("Top speed", UnitFormatter.speed(insights.highestSpeedKph, units, locale), Icons.Outlined.SportsMotorsports),
                insights.bestZeroToSixtyMillis?.let { InsightMetric("0–60 km/h", "%.1f s".format(locale, it / 1_000.0), Icons.Outlined.Timer) },
                insights.bestZeroToHundredMillis?.let { InsightMetric("0–100 km/h", "%.1f s".format(locale, it / 1_000.0), Icons.Outlined.Timer) },
                insights.peakAccelerationG?.let { InsightMetric("Peak acceleration", "%.2f g".format(locale, it), Icons.Outlined.Speed) },
                insights.peakBrakingG?.let { InsightMetric("Peak braking", "%.2f g".format(locale, it), Icons.Outlined.Speed) },
            ),
        )
    }
}

private data class InsightMetric(val label: String, val value: String, val icon: ImageVector)

/** A titled group of figures in two equal columns, each marked by its icon. */
@Composable
private fun MetricSection(title: String, metrics: List<InsightMetric>) {
    Column {
        SectionHeader(title)
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                metrics.chunked(2).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        row.forEach { (label, value, icon) ->
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Metric(label, value)
                            }
                        }
                        if (row.size < 2) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
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

    Card(Modifier.fillMaxWidth()) {
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

/** Filter chips, as on History, scrolling sideways rather than squeezing five labels into a row. */
@Composable
private fun PeriodSelector(selectedPeriod: InsightPeriod, onSelected: (InsightPeriod) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Periods.forEach { (period, label) ->
            FilterChip(selected = period == selectedPeriod, onClick = { onSelected(period) }, label = { Text(label) })
        }
    }
}

private val Periods = listOf(
    InsightPeriod.Today to "Today",
    InsightPeriod.ThisWeek to "This week",
    InsightPeriod.ThisMonth to "This month",
    InsightPeriod.LastThreeMonths to "Last 3 months",
    InsightPeriod.AllTime to "All",
)
