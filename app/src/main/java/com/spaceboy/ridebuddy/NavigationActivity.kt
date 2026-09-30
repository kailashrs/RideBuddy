package com.spaceboy.ridebuddy

import androidx.compose.runtime.getValue
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapsInitializer
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.libraries.navigation.NavigationView
import com.spaceboy.ridebuddy.core.navigation.NavigationDestination
import com.spaceboy.ridebuddy.core.navigation.NavigationSession
import com.spaceboy.ridebuddy.data.UnitFormatter
import com.spaceboy.ridebuddy.ui.theme.Rs457Theme
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * The turn-by-turn map: a view of [com.spaceboy.ridebuddy.core.navigation.NavigationController].
 *
 * Closing it does not stop navigation — guidance keeps running with the phone stowed. Only an
 * unstarted preview is abandoned when the screen goes away.
 */
class NavigationActivity : ComponentActivity() {
    private lateinit var navigationView: NavigationView
    private val controller get() = appContainer.navigationController
    private val setupError = mutableStateOf<String?>(null)
    private var previewPanelHeight = 0

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        if (hasLocationPermission()) begin() else setupError.value = "Precise location is required for turn-by-turn navigation"
    }

    private val bikeConnectionActions = BikeConnectionActions(this, onMessage = { message ->
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    })

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapsInitializer.initialize(applicationContext)
        navigationView = NavigationView(this).also { it.onCreate(savedInstanceState) }
        val settings = appContainer.appSettings.settings.value
        navigationView.setTrafficPromptsEnabled(settings.hazardAlerts)
        navigationView.setTrafficIncidentCardsEnabled(settings.hazardAlerts)

        val overlay = ComposeView(this).apply { setContent { Overlay() } }
        val root = FrameLayout(this).apply {
            val fill = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            addView(navigationView, fill)
            addView(overlay, fill)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            navigationView.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            insets
        }
        setContentView(root)
        ViewCompat.requestApplyInsets(root)

        var shown: NavigationSession = controller.session.value
        lifecycleScope.launch {
            controller.session.collect { session ->
                when (session) {
                    is NavigationSession.Ready -> showWholeRoute()
                    is NavigationSession.Guiding -> followRider()
                    // Stopped from elsewhere — the Live tab, the handlebar, or a lost link.
                    NavigationSession.Idle -> if (shown !is NavigationSession.Idle) finish()
                    else -> Unit
                }
                shown = session
            }
        }
        if (savedInstanceState == null) begin()
    }

    /** Checks the key and location, then attaches to the running route or prepares a new one. */
    private fun begin() {
        setupError.value = null
        lifecycleScope.launch {
            val key = appContainer.navigationApiKey.awaitLoaded()
            if (!key.isConfigured) {
                setupError.value = key.errorMessage ?: "Navigation API key is not configured"
                return@launch
            }
            if (!hasLocationPermission()) {
                permissionLauncher.launch(LocationPermissions)
                return@launch
            }
            val destination = intent.destination()
            if (destination == null) {
                if (controller.session.value !is NavigationSession.Guiding) {
                    setupError.value = getString(R.string.navigation_no_active_route)
                }
                return@launch
            }
            controller.prepare(this@NavigationActivity, destination)
            if (intent.getBooleanExtra(ExtraAutoStartGuidance, false)) controller.startGuidance()
        }
    }

    @androidx.compose.runtime.Composable
    private fun Overlay() {
        val settings by appContainer.appSettings.settings.collectAsStateWithLifecycle()
        val session by controller.session.collectAsStateWithLifecycle()
        val connection by appContainer.bikeConnection.connectionState.collectAsStateWithLifecycle()
        val association by appContainer.bikeCompanionManager.state.collectAsStateWithLifecycle()
        Rs457Theme(themeMode = settings.themeMode, dynamicColor = settings.dynamicColor, highContrast = settings.highContrast) {
            val error = setupError.value ?: (session as? NavigationSession.Failed)?.message
            when {
                error != null -> RetryCard(error)
                session is NavigationSession.Preparing || session is NavigationSession.Ready -> {
                    val ready = session as? NavigationSession.Ready
                    PreviewPanel(
                        title = session.destination?.title ?: "Route preview",
                        summary = ready?.summary(settings.distanceUnits) ?: getString(R.string.navigation_preparing_route),
                    ) {
                        NavigationStartControls(
                            routeReady = ready != null,
                            connection = connection,
                            paired = association.bike != null,
                            pairing = association.associationInProgress,
                            onGo = { lifecycleScope.launch { controller.startGuidance() } },
                            onConnect = bikeConnectionActions::requestConnection,
                        )
                    }
                }
                session is NavigationSession.Arrived ->
                    PreviewPanel(session.destination?.title.orEmpty(), getString(R.string.navigation_arrived)) {}
                else -> Unit
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun PreviewPanel(title: String, summary: String, controls: @androidx.compose.runtime.Composable () -> Unit) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp).onSizeChanged { size ->
                    if (previewPanelHeight != size.height) {
                        previewPanelHeight = size.height
                        if (controller.session.value is NavigationSession.Ready) showWholeRoute()
                    }
                },
                shape = MaterialTheme.shapes.extraLarge,
            ) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(summary, style = MaterialTheme.typography.bodyMedium)
                    controls()
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun RetryCard(message: String) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            ElevatedCard(
                modifier = Modifier.padding(32.dp),
                shape = MaterialTheme.shapes.extraLarge,
                colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
                    Button(onClick = ::begin) {
                        Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Text(getString(R.string.navigation_retry))
                    }
                }
            }
        }
    }

    private fun NavigationSession.Ready.summary(units: com.spaceboy.ridebuddy.data.DistanceUnits): String {
        val distance = distanceMetres?.let { UnitFormatter.distance(it / 1_000.0, units, Locale.getDefault()) }
        val minutes = durationSeconds?.let { (it.coerceAtLeast(0).toLong() + 59) / 60 }
        return listOfNotNull(distance, minutes?.let { if (it < 60) "$it min" else "${it / 60} hr ${it % 60} min" })
            .joinToString(" • ")
            .ifBlank { "Route ready from your current location" }
    }

    /** The SDK's own overview stops at 45 minutes; fit every segment for a full-trip preview. */
    private fun showWholeRoute() {
        val points = controller.currentNavigator?.routeSegments.orEmpty().flatMap { it.latLngs }
        if (points.isEmpty()) return
        val bounds = LatLngBounds.builder().apply { points.forEach(::include) }.build()
        navigationView.isNavigationUiEnabled = false
        navigationView.doOnLayout {
            navigationView.getMapAsync { map ->
                if (controller.session.value !is NavigationSession.Ready) return@getMapAsync
                val density = resources.displayMetrics.density
                map.setPadding(0, (24 * density).toInt(), 0, previewPanelHeight + (32 * density).toInt())
                map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, (32 * density).toInt()))
            }
        }
    }

    private fun followRider() {
        navigationView.isNavigationUiEnabled = true
        navigationView.getMapAsync { map ->
            map.setPadding(0, 0, 0, 0)
            // Checked inline so lint can see it; a revoked grant leaves the camera where it is.
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                map.followMyLocation(GoogleMap.CameraPerspective.TILTED)
            }
        }
    }

    private fun hasLocationPermission() = LocationPermissions.all { permission ->
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    override fun onStart() {
        super.onStart()
        navigationView.onStart()
    }

    override fun onResume() {
        super.onResume()
        navigationView.onResume()
    }

    override fun onPause() {
        navigationView.onPause()
        super.onPause()
    }

    override fun onStop() {
        navigationView.onStop()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        navigationView.onSaveInstanceState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        navigationView.onConfigurationChanged(newConfig)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        navigationView.onTrimMemory(level)
    }

    override fun onDestroy() {
        // A preview nobody started is abandoned with its screen; a running route carries on.
        val session = controller.session.value
        if (isFinishing && (session is NavigationSession.Preparing || session is NavigationSession.Ready ||
                session is NavigationSession.Failed)
        ) {
            appContainer.applicationScope.launch { controller.stop() }
        }
        navigationView.onDestroy()
        super.onDestroy()
    }

    companion object {
        private const val ExtraLatitude = "latitude"
        private const val ExtraLongitude = "longitude"
        private const val ExtraTitle = "title"
        private const val ExtraAutoStartGuidance = "auto_start_guidance"
        private val LocationPermissions = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )

        private fun Intent.destination(): NavigationDestination? {
            val latitude = getDoubleExtra(ExtraLatitude, Double.NaN)
            val longitude = getDoubleExtra(ExtraLongitude, Double.NaN)
            if (!latitude.isFinite() || !longitude.isFinite()) return null
            return NavigationDestination(latitude, longitude, getStringExtra(ExtraTitle) ?: "Destination")
        }

        /** Previews a route to [destination]; [autoStartGuidance] skips the Go when the bike is connected. */
        fun intent(context: Context, destination: NavigationDestination, autoStartGuidance: Boolean = false): Intent =
            Intent(context, NavigationActivity::class.java)
                .putExtra(ExtraLatitude, destination.latitude)
                .putExtra(ExtraLongitude, destination.longitude)
                .putExtra(ExtraTitle, destination.title)
                .putExtra(ExtraAutoStartGuidance, autoStartGuidance)

        /** Shows the route already running. */
        fun activeGuidanceIntent(context: Context): Intent = Intent(context, NavigationActivity::class.java)
    }
}
