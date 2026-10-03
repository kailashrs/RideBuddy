package com.spaceboy.ridebuddy

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.spaceboy.ridebuddy.data.rides
import com.spaceboy.ridebuddy.data.tripRoutes
import com.spaceboy.ridebuddy.ui.screens.TripDetailContent
import com.spaceboy.ridebuddy.ui.screens.TripEditorDialog
import com.spaceboy.ridebuddy.ui.theme.Rs457Theme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * One trip: its combined figures and its rides, with edit and delete.
 *
 * A separate Activity for the same reason as [RideDetailActivity]: it is reached from a History
 * card by id. It observes the repositories, so an edit here, or a ride deleted from inside it,
 * shows as soon as it is stored.
 */
class TripDetailActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val tripId = intent.getLongExtra(ExtraTripId, -1)
        val container = appContainer
        val appSettings = container.appSettings.settings.value
        setContent {
            Rs457Theme(themeMode = appSettings.themeMode, dynamicColor = appSettings.dynamicColor, highContrast = appSettings.highContrast) {
                val trips by container.tripRepository.trips.collectAsStateWithLifecycle()
                val allRides by container.rideRepository.rides.collectAsStateWithLifecycle()
                val trip = trips.firstOrNull { it.trip.id == tripId }
                val rides = remember(trip, allRides) { trip?.rides(allRides).orEmpty() }
                // Each ride's samples are a separate blob, so the routes load after the figures.
                val routes by produceState(emptyList<List<Pair<Double, Double>>>(), rides) {
                    value = tripRoutes(rides) { container.rideRepository.samples(it) }
                }
                var loaded by remember { mutableStateOf(false) }
                var editing by rememberSaveable { mutableStateOf(false) }
                var confirmDelete by rememberSaveable { mutableStateOf(false) }
                // The trips flow starts empty; only a trip missing after it has loaded is gone.
                LaunchedEffect(trip) { if (trip != null) loaded = true else if (loaded) finish() }

                if (editing && trip != null) {
                    TripEditorDialog(
                        title = "Edit trip",
                        initialName = trip.trip.name,
                        initialRideIds = trip.rideIds,
                        rides = allRides,
                        units = appSettings.distanceUnits,
                        onDismiss = { editing = false },
                        onSave = { name, rideIds ->
                            editing = false
                            lifecycleScope.launch { container.tripRepository.save(tripId, name, rideIds) }
                        },
                    )
                }
                if (confirmDelete) {
                    AlertDialog(
                        onDismissRequest = { confirmDelete = false },
                        title = { Text("Delete this trip?") },
                        text = { Text("Its rides stay in History.") },
                        confirmButton = {
                            TextButton(onClick = {
                                confirmDelete = false
                                deleteTrip(tripId)
                            }) { Text("Delete") }
                        },
                        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
                    )
                }
                val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
                Scaffold(
                    modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
                    topBar = {
                        TopAppBar(
                            title = { Text(trip?.trip?.name.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            navigationIcon = {
                                IconButton(onClick = ::finish) {
                                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                                }
                            },
                            actions = {
                                IconButton(onClick = { editing = true }, enabled = trip != null) {
                                    Icon(Icons.Outlined.Edit, contentDescription = "Edit trip")
                                }
                                var menuOpen by remember { mutableStateOf(false) }
                                Box {
                                    IconButton(onClick = { menuOpen = true }, enabled = trip != null) {
                                        Icon(Icons.Outlined.MoreVert, contentDescription = "More options")
                                    }
                                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                        DropdownMenuItem(
                                            text = { Text("Delete trip") },
                                            leadingIcon = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null) },
                                            onClick = { menuOpen = false; confirmDelete = true },
                                        )
                                    }
                                }
                            },
                            scrollBehavior = scrollBehavior,
                        )
                    },
                ) { padding ->
                    if (trip != null) {
                        TripDetailContent(
                            rides = rides,
                            units = appSettings.distanceUnits,
                            onRideSelected = { startActivity(RideDetailActivity.intent(this, it.id)) },
                            modifier = Modifier.padding(padding),
                            routes = routes,
                        )
                    }
                }
            }
        }
    }

    private fun deleteTrip(tripId: Long) {
        lifecycleScope.launch {
            try {
                appContainer.tripRepository.delete(tripId)
                finish()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                Toast.makeText(this@TripDetailActivity, "This trip could not be deleted", Toast.LENGTH_LONG).show()
            }
        }
    }

    companion object {
        private const val ExtraTripId = "trip_id"
        fun intent(context: Context, tripId: Long) = Intent(context, TripDetailActivity::class.java)
            .putExtra(ExtraTripId, tripId)
    }
}
