package com.spaceboy.ridebuddy.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.LocalParking
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.MapsInitializer
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberMarkerState
import com.spaceboy.ridebuddy.R
import com.spaceboy.ridebuddy.appContainer
import kotlinx.coroutines.launch

/** One or more recorded routes, each as latitude and longitude pairs, drawn as separate lines. */
internal typealias Routes = List<List<Pair<Double, Double>>>

/**
 * A map preview of [routes] with a Full map action, and Parking when [onOpenParking] is given.
 * One route for a ride; one per ride for a trip, so the gaps between rides stay gaps.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RouteCard(
    routes: Routes,
    fullMapTitle: String,
    finishLabel: String,
    mapDescription: String,
    onOpenParking: (() -> Unit)? = null,
) {
    var exploring by rememberSaveable { mutableStateOf(false) }
    OutlinedCard(Modifier.fillMaxWidth()) {
        if (!exploring) {
            RecordedRouteMap(routes, finishLabel, mapDescription, Modifier.fillMaxWidth().height(220.dp), interactive = false)
        }
        // An even pair across the card's width, so neither action reads as an afterthought.
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (onOpenParking != null) {
                OutlinedButton(onClick = onOpenParking, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.LocalParking, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text("Parking", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            FilledTonalButton(onClick = { exploring = true }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Outlined.Map, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(stringResource(R.string.navigation_full_map), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    if (exploring) {
        Dialog(onDismissRequest = { exploring = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                topBar = {
                    TopAppBar(
                        title = { Text(fullMapTitle) },
                        navigationIcon = {
                            IconButton(onClick = { exploring = false }) {
                                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                            }
                        },
                    )
                },
            ) { padding ->
                RecordedRouteMap(routes, finishLabel, mapDescription, Modifier.fillMaxSize().padding(padding), interactive = true)
            }
        }
    }
}

/** The full-screen map owns gestures, so panning never competes with the details list. */
@Composable
private fun RecordedRouteMap(
    routes: Routes,
    finishLabel: String,
    description: String,
    modifier: Modifier,
    interactive: Boolean,
) {
    val context = LocalContext.current
    var initialized by remember(context) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(context) {
        // The Activity can be restored directly after process death; wait for the key load.
        val key = context.appContainer.navigationApiKey
        key.awaitLoaded()
        initialized = key.isApplied && runCatching {
            MapsInitializer.initialize(context.applicationContext) == 0
        }.getOrDefault(false)
    }
    if (initialized != true) {
        Box(modifier, contentAlignment = Alignment.Center) {
            if (initialized == null) CircularProgressIndicator() else Text("Map unavailable")
        }
        return
    }
    val routeColor = MaterialTheme.colorScheme.primary
    val cameraPaddingPx = with(LocalDensity.current) { 48.dp.toPx().toInt() }
    val lines = remember(routes) { routes.map { route -> route.map { LatLng(it.first, it.second) } } }
    val bounds = remember(lines) { LatLngBounds.builder().apply { lines.flatten().forEach(::include) }.build() }
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
            modifier = Modifier.fillMaxSize().semantics { contentDescription = description },
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
            lines.forEach { Polyline(points = it, color = routeColor, width = 7f) }
            @Suppress("DEPRECATION")
            Marker(state = rememberMarkerState(position = lines.first().first()), title = "Start")
            @Suppress("DEPRECATION")
            Marker(state = rememberMarkerState(position = lines.last().last()), title = finishLabel)
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
