package com.spaceboy.ridebuddy

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListPrefetchScope
import androidx.compose.foundation.lazy.LazyListPrefetchStrategy
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.layout.NestedPrefetchScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.remember
import androidx.lifecycle.lifecycleScope
import androidx.core.net.toUri
import com.spaceboy.ridebuddy.data.Ride
import com.spaceboy.ridebuddy.data.RideEvent
import com.spaceboy.ridebuddy.data.RideEventType
import com.spaceboy.ridebuddy.data.RideEventDetector
import com.spaceboy.ridebuddy.data.TelemetryChartData
import com.spaceboy.ridebuddy.data.telemetryChartData
import com.spaceboy.ridebuddy.data.RideSample
import com.spaceboy.ridebuddy.data.DistanceUnits
import com.spaceboy.ridebuddy.data.UnitFormatter
import com.spaceboy.ridebuddy.ui.components.LineChart
import com.spaceboy.ridebuddy.ui.screens.formatDuration
import com.spaceboy.ridebuddy.ui.theme.Rs457Theme
import java.time.Instant
import java.io.Writer
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberMarkerState
import androidx.compose.runtime.rememberCoroutineScope
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds

/**
 * One ride in detail: summary, route map, telemetry charts, and export.
 *
 * A separate Activity because it is reached from a history row and takes a ride id rather
 * than any shared state. The full sample series is loaded here on demand — it can run to
 * tens of thousands of points and is deliberately not part of the history list — and
 * downsampled before anything is drawn.
 */
class RideDetailActivity : ComponentActivity() {
    private var loadState by mutableStateOf<RideDetailLoadState>(RideDetailLoadState.Loading)
    private var units by mutableStateOf(DistanceUnits.Metric)
    private val createCsvDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let { exportRideToUri(it, RideExportFormat.Csv) }
    }
    private val createGpxDocument = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/gpx+xml"),
    ) { uri ->
        uri?.let { exportRideToUri(it, RideExportFormat.Gpx) }
    }

    /**
     * Writes the ride to a document the rider picked.
     *
     * The ride is re-fetched rather than taken from the loaded state: the picker is a
     * separate Activity, so this process can be killed and recreated between choosing a
     * file and writing to it, leaving nothing loaded.
     */
    private fun exportRideToUri(uri: Uri, format: RideExportFormat) {
        lifecycleScope.launch {
            val container = appContainer
            val rideId = intent.getLongExtra(ExtraRideId, -1)
            var exportRide = (loadState as? RideDetailLoadState.Loaded)?.data?.ride
                ?: container.rideRepository.rides.value.firstOrNull { it.id == rideId }
            if (exportRide == null) {
                container.rideRepository.refresh()
                exportRide = container.rideRepository.rides.value.firstOrNull { it.id == rideId }
            }
            if (exportRide == null) {
                Toast.makeText(this@RideDetailActivity, "This ride is no longer available", Toast.LENGTH_LONG).show()
                return@launch
            }
            val exportSamples = container.rideRepository.samples(exportRide.id)
            val result = withContext(Dispatchers.IO) {
                try {
                    checkNotNull(contentResolver.openOutputStream(uri)) { "Could not open the selected document" }
                        .bufferedWriter().use { writer ->
                            when (format) {
                                RideExportFormat.Csv -> writer.writeCsv(exportSamples)
                                RideExportFormat.Gpx -> writer.writeGpx(exportRide, exportSamples)
                            }
                        }
                    Result.success(Unit)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Exception) {
                    Result.failure(error)
                }
            }
            if (result.isFailure) Toast.makeText(this@RideDetailActivity, "Could not export this ride", Toast.LENGTH_LONG).show()
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val rideId = intent.getLongExtra(ExtraRideId, -1)
        val container = appContainer
        val appSettings = container.appSettings.settings.value
        units = appSettings.distanceUnits
        loadRide(rideId)
        setContent {
            Rs457Theme(themeMode = appSettings.themeMode, dynamicColor = appSettings.dynamicColor, highContrast = appSettings.highContrast) {
                val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
                Scaffold(
                    modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
                    topBar = {
                        TopAppBar(
                            title = { Text("Ride details") },
                            navigationIcon = {
                                IconButton(onClick = ::finish) {
                                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                                }
                            },
                            scrollBehavior = scrollBehavior,
                        )
                    },
                ) { padding ->
                    when (val state = loadState) {
                        RideDetailLoadState.Loading -> {
                            Column(Modifier.fillMaxSize().padding(padding).padding(24.dp)) {
                                Text("Loading ride…")
                            }
                        }
                        is RideDetailLoadState.Error -> {
                            Column(
                                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Text(state.message, style = MaterialTheme.typography.titleMedium)
                                Button(onClick = { loadRide(rideId) }) { Text("Retry") }
                            }
                        }
                        is RideDetailLoadState.Loaded -> {
                            val currentRide = state.data.ride
                            RideDetailContent(
                                data = state.data,
                                units = units,
                                modifier = Modifier.padding(padding),
                                onExportCsv = { export("ride-${currentRide.id}.csv", RideExportFormat.Csv) },
                                onExportGpx = { export("ride-${currentRide.id}.gpx", RideExportFormat.Gpx) },
                                onShare = { shareRide(currentRide) },
                                onOpenParking = { openParking(currentRide) },
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * Loads the ride and its samples. Falls back to a repository refresh when the id is not
     * in the cached list, which is the case when this Activity is recreated on its own after
     * process death.
     */
    private fun loadRide(rideId: Long) {
        loadState = RideDetailLoadState.Loading
        lifecycleScope.launch {
            val container = appContainer
            try {
                var loadedRide = container.rideRepository.rides.value.firstOrNull { it.id == rideId }
                if (loadedRide == null) {
                    container.rideRepository.refresh()
                    loadedRide = container.rideRepository.rides.value.firstOrNull { it.id == rideId }
                }
                if (loadedRide == null) {
                    loadState = RideDetailLoadState.Error("This ride is no longer available")
                    return@launch
                }
                val resolvedRide = loadedRide
                val loadedSamples = container.rideRepository.samples(rideId)
                loadState = RideDetailLoadState.Loaded(
                    withContext(Dispatchers.Default) {
                        buildRideDetailUiData(resolvedRide, loadedSamples, units)
                    },
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                loadState = RideDetailLoadState.Error("This ride could not be loaded")
            }
        }
    }

    private fun export(fileName: String, format: RideExportFormat) {
        runCatching {
            when (format) {
                RideExportFormat.Csv -> createCsvDocument.launch(fileName)
                RideExportFormat.Gpx -> createGpxDocument.launch(fileName)
            }
        }.onFailure {
            Toast.makeText(this, R.string.document_provider_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    private fun shareRide(ride: Ride) {
        val summary = buildString {
            append("Ride: ${UnitFormatter.distance(ride.distanceKilometres, units, Locale.getDefault())}")
            append(" in ${formatDuration(ride.durationMillis)}, average ${UnitFormatter.speed(ride.averageSpeedKph, units, Locale.getDefault())}")
            ride.startArea?.let { append(" from $it") }
            ride.endArea?.let { append(" to $it") }
        }
        runCatching {
            startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, summary),
                    getString(R.string.ride_share_title),
                ),
            )
        }.onFailure {
            Toast.makeText(this, R.string.ride_share_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Opens the ride's end point in a maps app — where the bike was left. Silently does
     * nothing when the ride has no location, since the button is hidden in that case.
     */
    private fun openParking(ride: Ride) {
        val latitude = ride.endLatitude ?: return
        val longitude = ride.endLongitude ?: return
        val label = Uri.encode(ride.endArea ?: "Parked motorcycle")
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, "geo:$latitude,$longitude?q=$latitude,$longitude($label)".toUri()))
        }.onFailure {
            Toast.makeText(this, R.string.parking_map_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        private const val ExtraRideId = "ride_id"
        fun intent(context: Context, rideId: Long) = Intent(context, RideDetailActivity::class.java)
            .putExtra(ExtraRideId, rideId)
    }
}

private sealed interface RideDetailLoadState {
    data object Loading : RideDetailLoadState
    data class Loaded(val data: RideDetailUiData) : RideDetailLoadState
    data class Error(val message: String) : RideDetailLoadState
}

private data class RideDetailUiData(
    val ride: Ride,
    val hasSamples: Boolean,
    val hasLocations: Boolean,
    val routePoints: List<Pair<Double, Double>>,
    val speedValues: TelemetryChartData,
    val rpmValues: TelemetryChartData,
    val throttleValues: TelemetryChartData,
    val events: List<RideEvent>,
)

/**
 * Prepares everything the screen draws, in one pass off the main thread.
 *
 * Charts and the route are downsampled to their own limits: a chart is a few hundred pixels
 * wide, so more points than that are invisible, while a route can use more before its shape
 * stops improving. Values are converted to display units here rather than during
 * composition, so scrolling never re-converts them.
 */
private fun buildRideDetailUiData(
    ride: Ride,
    samples: List<RideSample>,
    units: DistanceUnits,
): RideDetailUiData {
    val routePoints = samples.mapNotNull { sample ->
        sample.latitude?.let { latitude ->
            sample.longitude?.takeIf { latitude.isFinite() && latitude in -90.0..90.0 && it.isFinite() && it in -180.0..180.0 }?.let { longitude -> latitude to longitude }
        }
    }
    return RideDetailUiData(
        ride = ride,
        hasSamples = samples.isNotEmpty(),
        hasLocations = routePoints.isNotEmpty(),
        routePoints = routePoints.ifEmpty { ride.routePreview.filter { it.isValid }.map { it.latitude to it.longitude } }.downsampled(MaxRoutePoints),
        speedValues = telemetryChartData(samples, MaxChartPoints) { UnitFormatter.chartSpeed(it.speedKph, units) },
        rpmValues = telemetryChartData(samples, MaxChartPoints) { it.rpm.toDouble() },
        throttleValues = telemetryChartData(samples, MaxChartPoints) { it.throttlePercent.toDouble() },
        events = RideEventDetector.detect(samples).take(MaxVisibleEvents),
    )
}

/**
 * Evenly spaced subset of at most [maxPoints], preserving the first and last elements.
 *
 * Index arithmetic is done in `Long` because the intermediate product of the source index
 * and the target index overflows `Int` for a long ride's sample count.
 */
private fun <T> List<T>.downsampled(maxPoints: Int): List<T> {
    require(maxPoints >= 2)
    if (size <= maxPoints) return this
    val sourceLastIndex = lastIndex.toLong()
    val targetLastIndex = maxPoints - 1L
    return List(maxPoints) { targetIndex ->
        this[(targetIndex.toLong() * sourceLastIndex / targetLastIndex).toInt()]
    }
}

/** Roughly one point per pixel of chart width; more cannot be seen. */
private const val MaxChartPoints = 600

/** A route tolerates more detail than a chart before its shape stops improving. */
private const val MaxRoutePoints = 1_000

/** Events listed. A long ride can produce hundreds, which is not a readable list. */
private const val MaxVisibleEvents = 20

/**
 * No-op prefetch strategy used to bypass the LazyColumn prefetch scheduler.
 *
 * The AndroidView that hosts a Google Maps `MapView` inside [RouteCard] is expensive
 * to instantiate (loads the Maps SDK, allocates a GL surface) and races with
 * `AndroidPrefetchScheduler`'s cancellation path when the user scrolls. The race
 * throws `IllegalArgumentException: Cannot disable reuse from root if it was caused
 * by other groups` from `GapComposer.endReuseFromRoot$runtime` and crashes the
 * activity. Disabling prefetch via `rememberLazyListState(prefetchStrategy = …)`
 * is the documented Compose mitigation when an `AndroidView` cannot tolerate
 * being constructed inside a paused prefetch composition.
 *
 * We override only the callback hooks (not `prefetchScheduler`) — the default
 * scheduler is attached internally, but with no-op callbacks it never receives
 * any candidate index to schedule.
 */
@OptIn(ExperimentalFoundationApi::class)
private val NoOpLazyListPrefetchStrategy = object : LazyListPrefetchStrategy {
    override fun LazyListPrefetchScope.onScroll(delta: Float, layoutInfo: LazyListLayoutInfo) = Unit
    override fun LazyListPrefetchScope.onVisibleItemsUpdated(layoutInfo: LazyListLayoutInfo) = Unit
    override fun NestedPrefetchScope.onNestedPrefetch(firstVisibleItemIndex: Int) = Unit
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun RideDetailContent(
    data: RideDetailUiData,
    units: DistanceUnits,
    modifier: Modifier = Modifier,
    onExportCsv: () -> Unit,
    onExportGpx: () -> Unit,
    onShare: () -> Unit,
    onOpenParking: () -> Unit,
) {
    val ride = data.ride
    val locale = LocalConfiguration.current.locales[0]
    // Prefetch is disabled because the RouteCard item hosts an AndroidView(MapView)
    // that races with AndroidPrefetchScheduler's cancellation (see NoOpLazyListPrefetchStrategy).
    val listState = rememberLazyListState(prefetchStrategy = NoOpLazyListPrefetchStrategy)
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "header_info", contentType = "header") {
            Text(UnitFormatter.formatDateTime(ride.startedAtMillis), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text("${UnitFormatter.distance(ride.distanceKilometres, units, locale)} • ${formatDuration(ride.durationMillis)} • ${UnitFormatter.speed(ride.averageSpeedKph, units, locale)} average", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (data.routePoints.size > 1) {
            item(key = "route_card", contentType = "route_map") {
                RouteCard(data.routePoints)
            }
        }
        item(key = "ride_summary", contentType = "summary_card") {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Ride summary", style = MaterialTheme.typography.titleMedium)
                    Text("Estimated fuel ${UnitFormatter.fuel(ride.estimatedFuelLitres, units, locale)} • ${UnitFormatter.mileage(ride.averageMileageKilometresPerLitre, units, locale)}", style = MaterialTheme.typography.bodyMedium)
                    if (ride.telemetryDurationMillis != null && ride.durationMillis - ride.telemetryDurationMillis > 2_500L) {
                        Text("Telemetry gaps are excluded from distance and averages.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("Peak ${UnitFormatter.speed(ride.maximumSpeedKph, units, locale)} • ${ride.maximumRpm} rpm", style = MaterialTheme.typography.bodyMedium)
                    ride.zeroToSixtyMillis?.let { Text("0–60 km/h ${"%.1f".format(locale, it / 1_000.0)} s", style = MaterialTheme.typography.bodyMedium) }
                    ride.zeroToHundredMillis?.let { Text("0–100 km/h ${"%.1f".format(locale, it / 1_000.0)} s", style = MaterialTheme.typography.bodyMedium) }
                    if (ride.startArea != null || ride.endArea != null) Text("${ride.startArea ?: "Start"} → ${ride.endArea ?: "Parking location"}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item(key = "chart_speed", contentType = "telemetry_chart") {
            TelemetryChart("Speed", UnitFormatter.speedUnit(units), data.speedValues)
        }
        item(key = "chart_rpm", contentType = "telemetry_chart") {
            TelemetryChart("Engine speed", "rpm", data.rpmValues)
        }
        item(key = "chart_throttle", contentType = "telemetry_chart") {
            TelemetryChart("Throttle", "%", data.throttleValues)
        }
        item(key = "ride_events", contentType = "events_card") {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Ride events", style = MaterialTheme.typography.titleMedium)
                    if (data.events.isEmpty()) Text("No hard acceleration or braking events detected", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    data.events.forEach { event ->
                        val label = if (event.type == RideEventType.HardAcceleration) "Hard acceleration" else "Hard braking"
                        Text("%s • %s • %+.1f m/s²".format(locale, label, UnitFormatter.formatTime(event.timestampMillis), event.accelerationMetresPerSecondSquared), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        item(key = "export_buttons", contentType = "action_buttons") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onExportCsv, modifier = Modifier.weight(1f), enabled = data.hasSamples) { Text("Export CSV") }
                Button(onClick = onExportGpx, modifier = Modifier.weight(1f), enabled = data.hasLocations) { Text("Export GPX") }
            }
        }
        item(key = "share_buttons", contentType = "action_buttons") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onShare, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.Share, contentDescription = null)
                    Text("Share")
                }
                OutlinedButton(onClick = onOpenParking, modifier = Modifier.weight(1f), enabled = ride.endLatitude != null) {
                    Icon(Icons.Outlined.LocationOn, contentDescription = null)
                    Text("Parking")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RouteCard(points: List<Pair<Double, Double>>) {
    var exploring by rememberSaveable { mutableStateOf(false) }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Recorded route", style = MaterialTheme.typography.titleMedium)
            if (!exploring) RecordedRouteMap(points, Modifier.fillMaxWidth().height(240.dp), interactive = false)
            TextButton(onClick = { exploring = true }, modifier = Modifier.fillMaxWidth()) { Text("Explore route") }
        }
    }
    if (exploring) {
        Dialog(onDismissRequest = { exploring = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                topBar = {
                    TopAppBar(
                        title = { Text("Recorded route") },
                        navigationIcon = {
                            IconButton(onClick = { exploring = false }) {
                                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back to ride details")
                            }
                        },
                    )
                },
            ) { padding ->
                RecordedRouteMap(points, Modifier.fillMaxSize().padding(padding), interactive = true)
            }
        }
    }
}

/** The full-screen map owns gestures, so panning never competes with the details list. */
@Composable
private fun RecordedRouteMap(points: List<Pair<Double, Double>>, modifier: Modifier, interactive: Boolean) {
    val routeColor = MaterialTheme.colorScheme.primary
    val cameraPaddingPx = with(LocalDensity.current) { 48.dp.toPx().toInt() }
    val route = remember(points) { points.map { LatLng(it.first, it.second) } }
    val bounds = remember(route) { LatLngBounds.builder().apply { route.forEach(::include) }.build() }
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(bounds.center, 12f)
    }
    val scope = rememberCoroutineScope()
    var loaded by remember { mutableStateOf(false) }
    val fitRoute: () -> Unit = {
        scope.launch {
            cameraPositionState.move(
                if (bounds.southwest == bounds.northeast) CameraUpdateFactory.newLatLngZoom(bounds.center, 15f)
                else CameraUpdateFactory.newLatLngBounds(bounds, cameraPaddingPx),
            )
        }
    }
    Box(modifier) {
        GoogleMap(
            modifier = Modifier.fillMaxSize().semantics { contentDescription = "Recorded ride map with start and parking markers" },
            cameraPositionState = cameraPositionState,
            properties = MapProperties(isMyLocationEnabled = false),
            uiSettings = MapUiSettings(
                zoomControlsEnabled = interactive,
                mapToolbarEnabled = interactive,
                compassEnabled = interactive,
                myLocationButtonEnabled = false,
                scrollGesturesEnabled = interactive,
                zoomGesturesEnabled = interactive,
                rotationGesturesEnabled = interactive,
                tiltGesturesEnabled = false,
            ),
            onMapLoaded = { loaded = true; fitRoute() },
        ) {
            Polyline(points = route, color = routeColor, width = 7f)
            @Suppress("DEPRECATION")
            Marker(state = rememberMarkerState(position = route.first()), title = "Start")
            @Suppress("DEPRECATION")
            Marker(state = rememberMarkerState(position = route.last()), title = "Parking location")
        }
        if (interactive) {
            Button(
                onClick = fitRoute,
                enabled = loaded,
                modifier = Modifier.align(Alignment.TopCenter).padding(12.dp),
            ) { Text("Show whole route") }
        }
    }
}

@Composable
private fun TelemetryChart(title: String, unit: String, series: TelemetryChartData) {
    val values = series.values
    val locale = LocalConfiguration.current.locales[0]
    val color = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            val maximum = values.filterNotNull().maxOrNull()
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(maximum?.let { "Peak %.0f %s".format(locale, it, unit) } ?: "No data", color = MaterialTheme.colorScheme.onSurfaceVariant)
            LineChart(
                values = values,
                timestampsMillis = series.timestampsMillis,
                height = 140.dp,
                topPadding = 12.dp,
                color = color,
                // The unit is an argument, not part of the format string. Splicing it in meant
                // the throttle chart's "%" produced a format string ending in a bare percent,
                // which String.format rejects — crashing on any ride that had throttle data.
                contentDescription = maximum?.let {
                    "%s over the duration of the ride; peak %.0f %s".format(locale, title, it, unit)
                }
                    ?: "$title data unavailable",
                strokeWidth = 5f,
                fillAlpha = null,
                drawBaseline = true,
                baselineColor = grid,
            )
            if (series.timestampsMillis.isNotEmpty()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(UnitFormatter.formatTime(series.timestampsMillis.first()), style = MaterialTheme.typography.labelSmall)
                    Text(UnitFormatter.formatTime(series.timestampsMillis.last()), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

/** CSV for spreadsheets and analysis; GPX for mapping and fitness tools. */
private enum class RideExportFormat { Csv, Gpx }

/**
 * Full sample series as CSV. Timestamps are ISO-8601 and every value is in SI units,
 * independent of the rider's display preference, so an export is self-describing.
 */
private fun Writer.writeCsv(samples: List<RideSample>) {
    appendLine("timestamp_iso,speed_kph,rpm,throttle_percent,mileage_km_per_litre,acceleration_mps2,latitude,longitude,accuracy_m,altitude_m")
    samples.forEach { sample ->
        appendLine(listOf(Instant.ofEpochMilli(sample.timestampMillis), sample.speedKph, sample.rpm, sample.throttlePercent, sample.mileageKilometresPerLitre ?: "", sample.accelerationMetresPerSecondSquared, sample.latitude ?: "", sample.longitude ?: "", sample.accuracyMetres ?: "", sample.altitudeMetres ?: "").joinToString(","))
    }
}

/**
 * The route as a GPX 1.1 track. Samples without a location are skipped rather than emitted
 * as zeroes, which would draw a line through the Gulf of Guinea. Coordinates are formatted
 * with [Locale.US] because GPX requires a dot decimal separator regardless of locale.
 */
private fun Writer.writeGpx(ride: Ride, samples: List<RideSample>) {
    append("<?xml version=\"1.0\" encoding=\"UTF-8\"?><gpx version=\"1.1\" creator=\"RideBuddy\" xmlns=\"http://www.topografix.com/GPX/1/1\"><trk><name>Ride ")
    append(ride.id.toString())
    append("</name><trkseg>")
    samples.forEach { sample ->
        val lat = sample.latitude ?: return@forEach
        val lon = sample.longitude ?: return@forEach
        if (!lat.isFinite() || lat !in -90.0..90.0 || !lon.isFinite() || lon !in -180.0..180.0) return@forEach
        append(String.format(Locale.US, "<trkpt lat=\"%.7f\" lon=\"%.7f\">", lat, lon))
        sample.altitudeMetres?.takeIf(Double::isFinite)?.let { append(String.format(Locale.US, "<ele>%.2f</ele>", it)) }
        append("<time>").append(Instant.ofEpochMilli(sample.timestampMillis).toString()).append("</time></trkpt>")
    }
    append("</trkseg></trk></gpx>")
}
