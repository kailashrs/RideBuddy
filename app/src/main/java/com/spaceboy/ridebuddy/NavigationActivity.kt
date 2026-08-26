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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Navigation
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
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
import com.google.android.libraries.navigation.Waypoint
import com.spaceboy.ridebuddy.domain.BikeControlEvent
import com.spaceboy.ridebuddy.service.NavInfoReceivingService
import com.spaceboy.ridebuddy.ui.theme.Rs457Theme
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.launch

class NavigationActivity : ComponentActivity() {
    private lateinit var navigationView: NavigationView
    private var statusTextState = mutableStateOf("")
    private var retryVisibleState = mutableStateOf(false)
    private var navigator: Navigator? = null
    private var guidanceStarted = false
    private var navigationEndedByUser = false
    private val navigationSessionId = NextNavigationSessionId.incrementAndGet()

    private val reroutingListener = Navigator.ReroutingListener {
        if (!NavigationSessionOwners.isOwner(navigationSessionId)) return@ReroutingListener
        appContainer.apply {
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

        val container = appContainer
        NavigationSessionOwners.register(navigationSessionId)
        container.navigationGuidanceLifecycle.registerPendingSession(navigationSessionId)

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
                                                if (intent.getBooleanExtra(ExtraAttachExistingGuidance, false)) {
                                                    navigator?.takeUnless { it.isGuidanceRunning }?.let { currentNavigator ->
                                                        currentNavigator.removeReroutingListener(reroutingListener)
                                                        releaseNavigationSession(currentNavigator, stopGuidance = false)
                                                    }
                                                    navigator = null
                                                    initializeNavigation()
                                                } else {
                                                    navigator?.let(::calculateRoute) ?: initializeNavigation()
                                                }
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
            appContainer.bikeConnection.controls.collect { event ->
                if (!NavigationSessionOwners.isOwner(navigationSessionId)) return@collect
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
        runCatching {
            NavigationApi.getNavigator(this, object : NavigationApi.NavigatorListener {
                override fun onNavigatorReady(readyNavigator: Navigator) {
                    if (isFinishing || isDestroyed) {
                        if ((navigationEndedByUser || !readyNavigator.isGuidanceRunning) &&
                            // Cleanup path: only release if no live session has taken ownership.
                            NavigationSessionOwners.claim(navigationSessionId)
                        ) {
                            releaseNavigationSession(readyNavigator, stopGuidance = navigationEndedByUser)
                        }
                        return
                    }
                    if (!NavigationSessionOwners.claim(navigationSessionId)) return
                    navigator = readyNavigator
                    if (!configureNavigator(readyNavigator)) {
                        navigator = null
                        NavigationSessionOwners.release(navigationSessionId)
                        return
                    }

                    val attachRequested = intent.getBooleanExtra(ExtraAttachExistingGuidance, false)
                    when (
                        navigationLaunchPolicy(
                            attachRequested = attachRequested,
                            guidanceWasStarted = guidanceStarted,
                            guidanceIsRunning = readyNavigator.isGuidanceRunning,
                        )
                    ) {
                        NavigationLaunchPolicy.AttachExisting -> {
                            guidanceStarted = true
                            appContainer.navigationGuidanceLifecycle
                                .markGuidanceStarted(navigationSessionId)
                            retryVisibleState.value = false
                            statusTextState.value = getString(R.string.navigation_active)
                        }
                        NavigationLaunchPolicy.NoActiveRoute -> {
                            guidanceStarted = false
                            showError(getString(R.string.navigation_no_active_route))
                        }
                        NavigationLaunchPolicy.PrepareNewRoute -> prepareNewRoute(readyNavigator)
                    }
                }

                override fun onError(errorCode: Int) {
                    appContainer.navigationGuidanceLifecycle
                        .abandonPendingSession(navigationSessionId)
                    if (!NavigationSessionOwners.isCurrent(navigationSessionId) || isFinishing || isDestroyed) return
                    showError(getString(R.string.navigation_start_failed, errorCode))
                }
            })
        }.onFailure {
            appContainer.navigationGuidanceLifecycle
                .abandonPendingSession(navigationSessionId)
            if (!NavigationSessionOwners.isCurrent(navigationSessionId) || isFinishing || isDestroyed) return@onFailure
            showError(getString(R.string.navigation_start_failed_unknown))
        }
    }

    private fun configureNavigator(readyNavigator: Navigator): Boolean {
        navigationView.isNavigationUiEnabled = true
        val container = appContainer
        val settings = container.appSettings.settings.value
        navigationView.setTrafficPromptsEnabled(settings.hazardAlerts)
        navigationView.setTrafficIncidentCardsEnabled(settings.hazardAlerts)
        readyNavigator.addReroutingListener(reroutingListener)
        readyNavigator.setTaskRemovedBehavior(Navigator.TaskRemovedBehavior.CONTINUE_SERVICE)
        readyNavigator.setAudioGuidance(
            if (settings.voiceGuidance) Navigator.AudioGuidance.VOICE_ALERTS_AND_GUIDANCE
            else Navigator.AudioGuidance.SILENT,
        )
        readyNavigator.setSpeedAlertOptions(SpeedAlertOptions(0.05f, 0.15f, 5.0))
        val attached = container.navigationGuidanceLifecycle.attach(
            sessionId = navigationSessionId,
            navigator = readyNavigator,
            onFinalArrival = finalArrival@{
                if (!NavigationSessionOwners.isOwner(navigationSessionId) || navigator !== readyNavigator) {
                    return@finalArrival
                }
                guidanceStarted = false
                readyNavigator.removeReroutingListener(reroutingListener)
                runOnUiThread { statusTextState.value = getString(R.string.navigation_arrived) }
            },
            onSpeeding = speeding@{ percentageAboveLimit ->
                val speed = container.bikeConnection.telemetry.value
                    ?.speedKilometresPerHour ?: return@speeding
                if (percentageAboveLimit >= 0f && speed > 0.0) {
                    val limit = (speed / (1.0 + percentageAboveLimit)).div(5.0).toInt().times(5)
                    if (limit > 0) container.tftNavigationBridge.speedLimit(limit)
                }
            },
        )
        if (!attached) {
            readyNavigator.removeReroutingListener(reroutingListener)
        }
        return attached
    }

    private fun prepareNewRoute(readyNavigator: Navigator) {
        val container = appContainer
        if (readyNavigator.isGuidanceRunning) {
            runCatching(readyNavigator::stopGuidance)
            runCatching(readyNavigator::unregisterServiceForNavUpdates)
            guidanceStarted = false
            container.navigationFeed.clear()
            container.tftNavigationBridge.stop()
        }
        val options = NavigationUpdatesOptions.builder().setNumNextStepsToPreview(1).build()
        container.tftNavigationBridge.start()
        val navUpdatesRegistered = runCatching {
            readyNavigator.registerServiceForNavUpdates(
                packageName,
                NavInfoReceivingService::class.java.name,
                options,
            )
        }.getOrDefault(false)
        if (!navUpdatesRegistered) {
            container.tftNavigationBridge.stop()
            Toast.makeText(this, R.string.navigation_tft_updates_unavailable, Toast.LENGTH_LONG).show()
        }
        calculateRoute(readyNavigator)
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
        val preferences = appContainer.appSettings.settings.value
        val routing = RoutingOptions()
            .travelMode(RoutingOptions.TravelMode.TWO_WHEELER)
            .avoidTolls(preferences.avoidTolls)
            .avoidHighways(preferences.avoidHighways)
            .avoidFerries(preferences.avoidFerries)
        navigator.setDestination(waypoint, routing).setOnResultListener { status ->
            runOnUiThread {
                if (isFinishing || isDestroyed ||
                    !NavigationSessionOwners.isOwner(navigationSessionId) || this.navigator !== navigator
                ) return@runOnUiThread
                if (status == Navigator.RouteStatus.OK) {
                    retryVisibleState.value = false
                    statusTextState.value = intent.getStringExtra(ExtraTitle) ?: "Navigation active"
                    val startResult = runCatching(navigator::startGuidance)
                    if (startResult.isSuccess) {
                        guidanceStarted = true
                        appContainer.navigationGuidanceLifecycle
                            .markGuidanceStarted(navigationSessionId)
                    } else {
                        guidanceStarted = false
                        runCatching(navigator::unregisterServiceForNavUpdates)
                        clearNavigationOutput()
                        showError(getString(R.string.navigation_start_failed_unknown))
                    }
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
        val currentNavigator = navigator
        currentNavigator?.removeReroutingListener(reroutingListener)
        val continueInBackground = shouldKeepGuidanceInBackground(
            navigationEndedByUser = navigationEndedByUser,
            guidanceStarted = guidanceStarted,
            guidanceIsRunning = currentNavigator?.isGuidanceRunning == true,
        )
        if (!continueInBackground) {
            if (currentNavigator != null) {
                releaseNavigationSession(currentNavigator, stopGuidance = navigationEndedByUser)
            } else if (navigationEndedByUser) {
                clearNavigationOutput()
            }
        } else {
            appContainer.navigationGuidanceLifecycle.detachUi(navigationSessionId)
            NavigationSessionOwners.release(navigationSessionId)
        }
        if (currentNavigator == null) {
            appContainer.navigationGuidanceLifecycle
                .abandonPendingSession(navigationSessionId)
        }
        navigationView.onDestroy()
        super.onDestroy()
    }

    private fun releaseNavigationSession(target: Navigator, stopGuidance: Boolean) {
        if (!NavigationSessionOwners.release(navigationSessionId)) {
            if (navigator === target) navigator = null
            guidanceStarted = false
            return
        }
        appContainer.navigationGuidanceLifecycle
            .release(navigationSessionId, target)
        if (stopGuidance) runCatching(target::stopGuidance)
        runCatching(target::unregisterServiceForNavUpdates)
        runCatching(target::cleanup)
        if (navigator === target) navigator = null
        guidanceStarted = false
        clearNavigationOutput()
    }

    private fun clearNavigationOutput() {
        appContainer.apply {
            navigationFeed.clear()
            runCatching(tftNavigationBridge::stop)
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        navigationView.onTrimMemory(level)
    }

    companion object {
        private const val ExtraLatitude = "latitude"
        private const val ExtraLongitude = "longitude"
        private const val ExtraTitle = "title"
        private const val ExtraAttachExistingGuidance = "attach_existing_guidance"
        private const val KeyGuidanceStarted = "guidance_started"
        private val NextNavigationSessionId = AtomicLong()
        private val NavigationSessionOwners = NavigationSessionOwnership()
        private val LocationPermissions = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )

        fun intent(context: Context, latitude: Double, longitude: Double, title: String): Intent =
            Intent(context, NavigationActivity::class.java)
                .putExtra(ExtraLatitude, latitude)
                .putExtra(ExtraLongitude, longitude)
                .putExtra(ExtraTitle, title)

        fun activeGuidanceIntent(context: Context): Intent =
            Intent(context, NavigationActivity::class.java)
                .putExtra(ExtraAttachExistingGuidance, true)
    }
}

internal enum class NavigationLaunchPolicy {
    AttachExisting,
    PrepareNewRoute,
    NoActiveRoute,
}

internal fun navigationLaunchPolicy(
    attachRequested: Boolean,
    guidanceWasStarted: Boolean,
    guidanceIsRunning: Boolean,
): NavigationLaunchPolicy = when {
    guidanceIsRunning && (attachRequested || guidanceWasStarted) -> NavigationLaunchPolicy.AttachExisting
    attachRequested -> NavigationLaunchPolicy.NoActiveRoute
    else -> NavigationLaunchPolicy.PrepareNewRoute
}

internal fun shouldKeepGuidanceInBackground(
    navigationEndedByUser: Boolean,
    guidanceStarted: Boolean,
    guidanceIsRunning: Boolean,
): Boolean = !navigationEndedByUser && (guidanceStarted || guidanceIsRunning)

internal class NavigationSessionOwnership {
    private var newestSessionId: Long? = null
    private var ownerId: Long? = null

    @Synchronized
    fun register(sessionId: Long) {
        if (sessionId > (newestSessionId ?: Long.MIN_VALUE)) {
            newestSessionId = sessionId
            ownerId = null
        }
    }

    @Synchronized
    fun claim(sessionId: Long): Boolean {
        if (newestSessionId != sessionId || ownerId != null) return false
        ownerId = sessionId
        return true
    }

    @Synchronized
    fun isCurrent(sessionId: Long): Boolean = newestSessionId == sessionId

    @Synchronized
    fun isOwner(sessionId: Long): Boolean = newestSessionId == sessionId && ownerId == sessionId

    @Synchronized
    fun release(sessionId: Long): Boolean {
        if (ownerId != sessionId) return false
        ownerId = null
        return true
    }
}
