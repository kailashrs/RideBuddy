package com.spaceboy.ridebuddy

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
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Navigation
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.google.android.libraries.navigation.NavigationApi
import com.google.android.libraries.navigation.NavigationUpdatesOptions
import com.google.android.libraries.navigation.NavigationView
import com.google.android.libraries.navigation.Navigator
import com.google.android.libraries.navigation.RoutingOptions
import com.google.android.libraries.navigation.SpeedAlertOptions
import com.google.android.libraries.navigation.SpeedingListener
import com.google.android.libraries.navigation.Waypoint
import com.spaceboy.ridebuddy.domain.BikeControlEvent
import com.spaceboy.ridebuddy.service.NavInfoReceivingService
import com.spaceboy.ridebuddy.ui.theme.Rs457Theme
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

class NavigationActivity : ComponentActivity() {
    private lateinit var navigationView: NavigationView
    private var statusTextState = mutableStateOf("")
    private var retryVisibleState = mutableStateOf(false)
    private var navigator: Navigator? = null
    private var guidanceStarted = false
    private var navigationEndedByUser = false

    private val arrivalListener = Navigator.ArrivalListener { event ->
        (application as Rs457Application).container.tftNavigationBridge.arrived()
        if (event.isFinalDestination) runOnUiThread { statusTextState.value = getString(R.string.navigation_arrived) }
    }
    private val reroutingListener = Navigator.ReroutingListener {
        (application as Rs457Application).container.apply {
            tftNavigationBridge.rerouting()
            val message = "The route is being recalculated; check for changed road conditions"
            if (ridingAlertMonitor.navigationHazard(message)) {
                tftPriorityCoordinator.presentTextAlert("ROUTE ALERT. Recalculating. Check road conditions.")
            }
        }
        runOnUiThread { statusTextState.value = getString(R.string.navigation_rerouting) }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        if (hasRequiredLocationPermissions()) initializeNavigation()
        else showError("Precise location is required for turn-by-turn navigation")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        guidanceStarted = savedInstanceState?.getBoolean(KeyGuidanceStarted, false) == true
        statusTextState.value = getString(R.string.navigation_preparing_route)
        navigationView = NavigationView(this).also { it.onCreate(savedInstanceState) }
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = finish()
            },
        )

        val container = (application as Rs457Application).container

        val composeOverlay = ComposeView(this).apply {
            setContent {
                val settings by container.appSettings.settings.collectAsStateWithLifecycle()

                Rs457Theme(
                    themeMode = settings.themeMode,
                    dynamicColor = settings.dynamicColor,
                    highContrast = settings.highContrast,
                ) {
                    // Retry Overlay Dialog
                    if (retryVisibleState.value) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            ElevatedCard(
                                modifier = Modifier.padding(32.dp),
                                shape = RoundedCornerShape(28.dp),
                                colors = CardDefaults.elevatedCardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                ),
                            ) {
                                Column(
                                    modifier = Modifier.padding(24.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(16.dp),
                                ) {
                                    Text(
                                        text = statusTextState.value,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                    Button(
                                        onClick = {
                                            if (hasRequiredLocationPermissions()) {
                                                navigator?.let(::calculateRoute) ?: initializeNavigation()
                                            } else {
                                                requestLocationOrInitialize()
                                            }
                                        },
                                    ) {
                                        Icon(Icons.Outlined.Refresh, contentDescription = null)
                                        Spacer(Modifier.width(8.dp))
                                        Text(getString(R.string.navigation_retry))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        val root = FrameLayout(this).apply {
            addView(
                navigationView,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
            addView(
                composeOverlay,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
        }

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val safeInsets = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            navigationView.setPadding(safeInsets.left, safeInsets.top, safeInsets.right, safeInsets.bottom)
            insets
        }

        setContentView(root)
        ViewCompat.requestApplyInsets(root)
        requestLocationOrInitialize()

        lifecycleScope.launch {
            (application as Rs457Application).container.bikeConnection.controls.collect { event ->
                when (event) {
                    BikeControlEvent.ExitNavigation -> endNavigationAndFinish()
                    BikeControlEvent.SkipManeuver -> {
                        val currentNavigator = navigator
                        if ((currentNavigator?.timeAndDistanceList?.size ?: 0) > 1) {
                            currentNavigator?.continueToNextDestination()
                        } else {
                            navigationView.showRouteOverview()
                        }
                    }
                    is BikeControlEvent.CallAction -> Unit
                }
            }
        }
    }

    private fun requestLocationOrInitialize() {
        if (hasRequiredLocationPermissions()) {
            initializeNavigation()
        } else {
            permissionLauncher.launch(LocationPermissions)
        }
    }

    private fun hasRequiredLocationPermissions(): Boolean = LocationPermissions.all { permission ->
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun initializeNavigation() {
        NavigationApi.getNavigator(this, object : NavigationApi.NavigatorListener {
            override fun onNavigatorReady(readyNavigator: Navigator) {
                if (isFinishing || isDestroyed) {
                    readyNavigator.cleanup()
                    return
                }
                navigator = readyNavigator
                navigationView.isNavigationUiEnabled = true
                val settings = (application as Rs457Application).container.appSettings.settings.value
                navigationView.setTrafficPromptsEnabled(settings.hazardAlerts)
                navigationView.setTrafficIncidentCardsEnabled(settings.hazardAlerts)
                if (readyNavigator.isGuidanceRunning) {
                    readyNavigator.stopGuidance()
                    readyNavigator.unregisterServiceForNavUpdates()
                    guidanceStarted = false
                    (application as Rs457Application).container.navigationFeed.clear()
                }
                val options = NavigationUpdatesOptions.builder().setNumNextStepsToPreview(1).build()
                val tftBridge = (application as Rs457Application).container.tftNavigationBridge
                tftBridge.start()
                val navUpdatesRegistered = runCatching {
                    readyNavigator.registerServiceForNavUpdates(
                        packageName,
                        NavInfoReceivingService::class.java.name,
                        options,
                    )
                }.getOrDefault(false)
                if (!navUpdatesRegistered) {
                    tftBridge.stop()
                    Toast.makeText(this@NavigationActivity, "TFT turn updates are unavailable", Toast.LENGTH_LONG)
                        .show()
                }
                readyNavigator.addArrivalListener(arrivalListener)
                readyNavigator.addReroutingListener(reroutingListener)
                readyNavigator.setTaskRemovedBehavior(Navigator.TaskRemovedBehavior.CONTINUE_SERVICE)
                readyNavigator.setAudioGuidance(
                    if (settings.voiceGuidance) Navigator.AudioGuidance.VOICE_ALERTS_AND_GUIDANCE
                    else Navigator.AudioGuidance.SILENT,
                )
                readyNavigator.setSpeedAlertOptions(SpeedAlertOptions(0.05f, 0.15f, 5.0))
                readyNavigator.setSpeedingListener(SpeedingListener { percentageAboveLimit, _ ->
                    val speed = (application as Rs457Application).container.bikeConnection.telemetry.value
                        ?.speedKilometresPerHour ?: return@SpeedingListener
                    if (percentageAboveLimit >= 0f && speed > 0.0) {
                        val limit = (speed / (1.0 + percentageAboveLimit)).div(5.0).toInt().times(5)
                        if (limit > 0) (application as Rs457Application).container.tftNavigationBridge.speedLimit(limit)
                    }
                })
                calculateRoute(readyNavigator)
            }

            override fun onError(errorCode: Int) {
                showError("Navigation could not start ($errorCode)")
            }
        })
    }

    private fun calculateRoute(navigator: Navigator) {
        val latitude = intent.getDoubleExtra(ExtraLatitude, Double.NaN)
        val longitude = intent.getDoubleExtra(ExtraLongitude, Double.NaN)
        if (!latitude.isFinite() || !longitude.isFinite()) {
            showError("The destination is invalid")
            return
        }
        val waypoint = Waypoint.builder()
            .setLatLng(latitude, longitude)
            .setTitle(intent.getStringExtra(ExtraTitle) ?: "Destination")
            .build()
        val preferences = (application as Rs457Application).container.appSettings.settings.value
        val routing = RoutingOptions()
            .travelMode(RoutingOptions.TravelMode.TWO_WHEELER)
            .avoidTolls(preferences.avoidTolls)
            .avoidHighways(preferences.avoidHighways)
            .avoidFerries(preferences.avoidFerries)
        navigator.setDestination(waypoint, routing).setOnResultListener { status ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (status == Navigator.RouteStatus.OK) {
                    retryVisibleState.value = false
                    statusTextState.value = intent.getStringExtra(ExtraTitle) ?: "Navigation active"
                    navigator.startGuidance()
                    guidanceStarted = true
                } else showError("Route unavailable: ${status.name.replace('_', ' ').lowercase()}")
            }
        }
    }

    private fun showError(message: String) {
        statusTextState.value = message
        retryVisibleState.value = true
    }

    private fun endNavigationAndFinish() {
        navigationEndedByUser = true
        finish()
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
        outState.putBoolean(KeyGuidanceStarted, guidanceStarted || navigator?.isGuidanceRunning == true)
        navigationView.onSaveInstanceState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        navigationView.onConfigurationChanged(newConfig)
    }

    override fun onDestroy() {
        navigator?.removeArrivalListener(arrivalListener)
        navigator?.removeReroutingListener(reroutingListener)
        navigator?.setSpeedingListener(null)
        if (navigationEndedByUser) {
            if (guidanceStarted) navigator?.stopGuidance()
            navigator?.unregisterServiceForNavUpdates()
            navigator?.cleanup()
            (application as Rs457Application).container.apply {
                navigationFeed.clear()
                tftNavigationBridge.stop()
            }
        }
        navigationView.onDestroy()
        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        navigationView.onTrimMemory(level)
    }

    companion object {
        private const val ExtraLatitude = "latitude"
        private const val ExtraLongitude = "longitude"
        private const val ExtraTitle = "title"
        private const val KeyGuidanceStarted = "guidance_started"
        private val LocationPermissions = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )

        fun intent(context: Context, latitude: Double, longitude: Double, title: String): Intent =
            Intent(context, NavigationActivity::class.java)
                .putExtra(ExtraLatitude, latitude)
                .putExtra(ExtraLongitude, longitude)
                .putExtra(ExtraTitle, title)
    }
}
