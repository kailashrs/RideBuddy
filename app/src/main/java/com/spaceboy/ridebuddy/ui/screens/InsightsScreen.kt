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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
    val locale = LocalConfiguration.current.locales[0]
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PeriodSelector(selectedPeriod, onPeriodSelected)
        if (insights.rideCount == 0) {
            EmptyState(
                icon = Icons.Outlined.Route,
                title = if (selectedPeriod == InsightPeriod.AllTime) "Your rides will appear here" else "No rides in this period",
                body = null,
                modifier = Modifier.heightIn(min = 320.dp),
            )
            return@Column
        }

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
                "Rides" to insights.rideCount.toString(),
                "Ride time" to formatDuration(insights.totalDurationMillis),
                "Fuel (est.)" to UnitFormatter.fuel(insights.estimatedFuelLitres, units, locale),
                "Mileage (est.)" to UnitFormatter.mileage(insights.averageMileageKilometresPerLitre, units, locale),
            ),
        )
        MetricSection(
            "Averages",
            listOf(
                "Distance" to UnitFormatter.distance(insights.averageRideDistanceKilometres, units, locale),
                "Duration" to formatDuration(insights.averageRideDurationMillis),
                "Speed" to UnitFormatter.speed(insights.averageSpeedKph, units, locale),
                "RPM" to "%.0f".format(locale, insights.averageRpm),
                "Throttle" to "%.0f%%".format(locale, insights.averageThrottlePercent),
            ),
        )
        MetricSection(
            "Records",
            listOfNotNull(
                "Longest ride" to UnitFormatter.distance(insights.longestRideKilometres, units, locale),
                "Top speed" to UnitFormatter.speed(insights.highestSpeedKph, units, locale),
                insights.bestZeroToSixtyMillis?.let { "0–60 km/h" to "%.1f s".format(locale, it / 1_000.0) },
                insights.bestZeroToHundredMillis?.let { "0–100 km/h" to "%.1f s".format(locale, it / 1_000.0) },
            ),
        )
    }
}

/** A titled group of figures in two equal columns. */
@Composable
private fun MetricSection(title: String, metrics: List<Pair<String, String>>) {
    Column {
        SectionHeader(title)
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                metrics.chunked(2).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        row.forEach { (label, value) -> Metric(label, value, Modifier.weight(1f)) }
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

@Composable
private fun PeriodSelector(selectedPeriod: InsightPeriod, onSelected: (InsightPeriod) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        Periods.forEachIndexed { index, (period, label) ->
            SegmentedButton(
                selected = period == selectedPeriod,
                onClick = { onSelected(period) },
                shape = SegmentedButtonDefaults.itemShape(index, Periods.size),
                modifier = Modifier.semantics { contentDescription = period.periodLabel() },
                // The check icon would not fit five segments at phone width; colour marks the choice.
                icon = {},
                label = { Text(label, maxLines = 1, softWrap = false) },
            )
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
