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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import androidx.lifecycle.lifecycleScope
import androidx.core.net.toUri
import com.spaceboy.ridebuddy.data.Ride
import com.spaceboy.ridebuddy.data.RideEvent
import com.spaceboy.ridebuddy.data.RideEventType
import com.spaceboy.ridebuddy.data.RideEventDetector
import com.spaceboy.ridebuddy.data.RideSample
import com.spaceboy.ridebuddy.data.DistanceUnits
import com.spaceboy.ridebuddy.data.UnitFormatter
import com.spaceboy.ridebuddy.ui.components.LineChart
import com.spaceboy.ridebuddy.ui.components.LineChartScalePolicy
import com.spaceboy.ridebuddy.ui.screens.formatDuration
import com.spaceboy.ridebuddy.ui.theme.Rs457Theme
import java.time.Instant
import java.io.Writer
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.PolylineOptions

class RideDetailActivity : ComponentActivity() {
    private var loadState by mutableStateOf<RideDetailLoadState>(RideDetailLoadState.Loading)
    private var units by mutableStateOf(DistanceUnits.Metric)
    private lateinit var appSettings: com.spaceboy.ridebuddy.data.AppSettings
    private var pendingExportFormat: RideExportFormat? = null
    private val createDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val format = pendingExportFormat
        pendingExportFormat = null
        if (uri == null || format == null) return@registerForActivityResult
        lifecycleScope.launch {
            val container = (application as Rs457Application).container
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
                runCatching {
                    checkNotNull(contentResolver.openOutputStream(uri)) { "Could not open the selected document" }
                        .bufferedWriter().use { writer ->
                            when (format) {
                                RideExportFormat.Csv -> writer.writeCsv(exportSamples)
                                RideExportFormat.Gpx -> writer.writeGpx(exportRide, exportSamples)
                            }
                        }
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
        pendingExportFormat = savedInstanceState?.getString(KeyPendingExportFormat)
            ?.let { name -> runCatching { RideExportFormat.valueOf(name) }.getOrNull() }
        val container = (application as Rs457Application).container
        appSettings = container.appSettings.settings.value
        units = appSettings.distanceUnits
        loadRide(rideId)
        setContent {
            Rs457Theme(themeMode = appSettings.themeMode, dynamicColor = appSettings.dynamicColor, highContrast = appSettings.highContrast) {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("Ride details") },
                            navigationIcon = {
                                IconButton(onClick = ::finish) {
                                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                                }
                            },
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

    private fun loadRide(rideId: Long) {
        loadState = RideDetailLoadState.Loading
        lifecycleScope.launch {
            val container = (application as Rs457Application).container
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
        pendingExportFormat = format
        createDocument.launch(fileName)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        pendingExportFormat?.let { outState.putString(KeyPendingExportFormat, it.name) }
        super.onSaveInstanceState(outState)
    }

    private fun shareRide(ride: Ride) {
        val summary = buildString {
            append("Ride: ${UnitFormatter.distance(ride.distanceKilometres, units, Locale.getDefault())}")
            append(" in ${formatDuration(ride.durationMillis)}, average ${UnitFormatter.speed(ride.averageSpeedKph, units, Locale.getDefault())}")
            ride.startArea?.let { append(" from $it") }
            ride.endArea?.let { append(" to $it") }
        }
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, summary), "Share ride"))
    }

    private fun openParking(ride: Ride) {
        val latitude = ride.endLatitude ?: return
        val longitude = ride.endLongitude ?: return
        val label = Uri.encode(ride.endArea ?: "Parked motorcycle")
        startActivity(Intent(Intent.ACTION_VIEW, "geo:$latitude,$longitude?q=$latitude,$longitude($label)".toUri()))
    }

    companion object {
        private const val ExtraRideId = "ride_id"
        private const val KeyPendingExportFormat = "pending_export_format"
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
    val speedValues: List<Double>,
    val rpmValues: List<Double>,
    val throttleValues: List<Double>,
    val mileageValues: List<Double>,
    val events: List<RideEvent>,
)

private fun buildRideDetailUiData(
    ride: Ride,
    samples: List<RideSample>,
    units: DistanceUnits,
): RideDetailUiData {
    val chartSamples = samples.downsampled(MaxChartPoints)
    val routePoints = samples.mapNotNull { sample ->
        sample.latitude?.let { latitude ->
            sample.longitude?.let { longitude -> latitude to longitude }
        }
    }
    return RideDetailUiData(
        ride = ride,
        hasSamples = samples.isNotEmpty(),
        hasLocations = routePoints.isNotEmpty(),
        routePoints = routePoints.downsampled(MaxRoutePoints),
        speedValues = chartSamples.map { UnitFormatter.chartSpeed(it.speedKph, units) },
        rpmValues = chartSamples.map { it.rpm.toDouble() },
        throttleValues = chartSamples.map { it.throttlePercent.toDouble() },
        mileageValues = chartSamples.mapNotNull { sample ->
            UnitFormatter.mileageValue(sample.consumptionLPer100Km, units, Locale.getDefault())
        },
        events = RideEventDetector.detect(samples).take(MaxVisibleEvents),
    )
}

private fun <T> List<T>.downsampled(maxPoints: Int): List<T> {
    require(maxPoints >= 2)
    if (size <= maxPoints) return this
    val sourceLastIndex = lastIndex.toLong()
    val targetLastIndex = maxPoints - 1L
    return List(maxPoints) { targetIndex ->
        this[(targetIndex.toLong() * sourceLastIndex / targetLastIndex).toInt()]
    }
}

private const val MaxChartPoints = 600
private const val MaxRoutePoints = 1_000
private const val MaxVisibleEvents = 20

@androidx.compose.runtime.Composable
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
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text(UnitFormatter.formatDateTime(ride.startedAtMillis), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text("${UnitFormatter.distance(ride.distanceKilometres, units, locale)} • ${formatDuration(ride.durationMillis)} • ${UnitFormatter.speed(ride.averageSpeedKph, units, locale)} average", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (data.routePoints.size > 1) item { RouteCard(data.routePoints) }
        item {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Ride summary", style = MaterialTheme.typography.titleMedium)
                    Text("Fuel used ${UnitFormatter.fuel(ride.estimatedFuelLitres, units, locale)} • ${UnitFormatter.consumption(ride.averageConsumptionLPer100Km, units, locale)}", style = MaterialTheme.typography.bodyMedium)
                    Text("Peak ${UnitFormatter.speed(ride.maximumSpeedKph, units, locale)} • ${ride.maximumRpm} rpm", style = MaterialTheme.typography.bodyMedium)
                    ride.zeroToSixtyMillis?.let { Text("0–60 km/h ${"%.1f".format(locale, it / 1_000.0)} s", style = MaterialTheme.typography.bodyMedium) }
                    ride.zeroToHundredMillis?.let { Text("0–100 km/h ${"%.1f".format(locale, it / 1_000.0)} s", style = MaterialTheme.typography.bodyMedium) }
                    if (ride.startArea != null || ride.endArea != null) Text("${ride.startArea ?: "Start"} → ${ride.endArea ?: "Parking location"}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item { TelemetryChart("Speed", UnitFormatter.speedUnit(units), data.speedValues) }
        item { TelemetryChart("Engine speed", "rpm", data.rpmValues) }
        item { TelemetryChart("Throttle", "%", data.throttleValues) }
        item { TelemetryChart("Mileage", UnitFormatter.mileageUnit(units), data.mileageValues) }
        item {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Ride events", style = MaterialTheme.typography.titleMedium)
                    if (data.events.isEmpty()) Text("No hard acceleration or braking events detected", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    data.events.forEach { event ->
                        val label = if (event.type == RideEventType.HardAcceleration) "Hard acceleration" else "Hard braking"
                        Text("$label • ${UnitFormatter.formatTime(event.timestampMillis)} • %+.1f m/s²".format(event.accelerationMetresPerSecondSquared), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onExportCsv, modifier = Modifier.weight(1f), enabled = data.hasSamples) { Text("Export CSV") }
                Button(onClick = onExportGpx, modifier = Modifier.weight(1f), enabled = data.hasLocations) { Text("Export GPX") }
            }
        }
        item {
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

@androidx.compose.runtime.Composable
@Suppress("DEPRECATION")
private fun RouteCard(points: List<Pair<Double, Double>>) {
    val color = MaterialTheme.colorScheme.primary
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mapState = rememberSaveable { Bundle() }
    val mapView = remember { MapView(context).apply { onCreate(mapState) } }
    DisposableEffect(mapView, lifecycleOwner) {
        var started = false
        var resumed = false
        var destroyed = false
        fun start() {
            if (!started && !destroyed) {
                mapView.onStart()
                started = true
            }
        }
        fun resume() {
            start()
            if (!resumed && !destroyed) {
                mapView.onResume()
                resumed = true
            }
        }
        fun pause() {
            if (resumed && !destroyed) {
                mapView.onPause()
                resumed = false
            }
        }
        fun stop() {
            pause()
            if (started && !destroyed) {
                mapView.onStop()
                started = false
            }
        }
        fun destroy() {
            if (destroyed) return
            stop()
            mapView.onSaveInstanceState(mapState)
            mapView.onDestroy()
            destroyed = true
        }

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> start()
                Lifecycle.Event.ON_RESUME -> resume()
                Lifecycle.Event.ON_PAUSE -> pause()
                Lifecycle.Event.ON_STOP -> stop()
                Lifecycle.Event.ON_DESTROY -> destroy()
                else -> Unit
            }
        }
        val memoryCallbacks = object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) mapView.onLowMemory()
            }

            @Suppress("OVERRIDE_DEPRECATION")
            override fun onLowMemory() = mapView.onLowMemory()
            override fun onConfigurationChanged(newConfig: Configuration) = Unit
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        context.applicationContext.registerComponentCallbacks(memoryCallbacks)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) start()
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) resume()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            context.applicationContext.unregisterComponentCallbacks(memoryCallbacks)
            destroy()
        }
    }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp)) {
            Text("Recorded route", style = MaterialTheme.typography.titleMedium)
            AndroidView(
                factory = { mapView },
                modifier = Modifier.fillMaxWidth().height(240.dp).padding(top = 12.dp)
                    .semantics { contentDescription = "Interactive map of the recorded ride" },
                update = { view ->
                    view.getMapAsync { map ->
                        map.clear()
                        val route = points.map { LatLng(it.first, it.second) }
                        map.addPolyline(PolylineOptions().addAll(route).color(color.toArgb()).width(7f))
                        map.addMarker(MarkerOptions().position(route.first()).title("Start"))
                        map.addMarker(MarkerOptions().position(route.last()).title("Parking location"))
                        val bounds = LatLngBounds.builder().apply { route.forEach(::include) }.build()
                        map.setOnMapLoadedCallback {
                            val update = CameraUpdateFactory.newLatLngBounds(bounds, 64)
                            map.moveCamera(update)
                        }
                    }
                },
            )
        }
    }
}

@androidx.compose.runtime.Composable
private fun TelemetryChart(title: String, unit: String, values: List<Double>) {
    val color = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp)) {
            val maximum = values.maxOrNull() ?: 0.0
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text("Peak %.1f %s".format(maximum, unit), color = MaterialTheme.colorScheme.onSurfaceVariant)
            LineChart(
                values = values,
                height = 140.dp,
                topPadding = 12.dp,
                color = color,
                contentDescription = "$title over the duration of the ride; peak %.1f $unit".format(maximum),
                scalePolicy = LineChartScalePolicy.ZeroBased,
                clampNegativeValues = true,
                smooth = false,
                strokeWidth = 5f,
                fillAlpha = null,
                drawBaseline = true,
                baselineColor = grid,
            )
        }
    }
}

private enum class RideExportFormat { Csv, Gpx }

private fun Writer.writeCsv(samples: List<RideSample>) {
    appendLine("timestamp_iso,speed_kph,rpm,throttle_percent,consumption_l_per_100km,acceleration_mps2,latitude,longitude,accuracy_m,altitude_m")
    samples.forEach { sample ->
        appendLine(listOf(Instant.ofEpochMilli(sample.timestampMillis), sample.speedKph, sample.rpm, sample.throttlePercent, sample.consumptionLPer100Km, sample.accelerationMetresPerSecondSquared, sample.latitude ?: "", sample.longitude ?: "", sample.accuracyMetres ?: "", sample.altitudeMetres ?: "").joinToString(","))
    }
}

private fun Writer.writeGpx(ride: Ride, samples: List<RideSample>) {
    append("<?xml version=\"1.0\" encoding=\"UTF-8\"?><gpx version=\"1.1\" creator=\"RideBuddy\" xmlns=\"http://www.topografix.com/GPX/1/1\"><trk><name>Ride ")
    append(ride.id.toString())
    append("</name><trkseg>")
    samples.forEach { sample ->
        val lat = sample.latitude ?: return@forEach
        val lon = sample.longitude ?: return@forEach
        append(String.format(Locale.US, "<trkpt lat=\"%.7f\" lon=\"%.7f\">", lat, lon))
        sample.altitudeMetres?.let { append(String.format(Locale.US, "<ele>%.2f</ele>", it)) }
        append("<time>").append(Instant.ofEpochMilli(sample.timestampMillis).toString()).append("</time></trkpt>")
    }
    append("</trkseg></trk></gpx>")
}
