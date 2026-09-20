package com.spaceboy.ridebuddy

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Navigation
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.view.doOnLayout
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.libraries.navigation.NavigationApi
import com.google.android.libraries.navigation.NavigationUpdatesOptions
import com.google.android.libraries.navigation.NavigationView
import com.google.android.libraries.navigation.Navigator
import com.google.android.libraries.navigation.RoutingOptions
import com.google.android.libraries.navigation.SpeedAlertOptions
import com.google.android.libraries.navigation.Waypoint
import com.spaceboy.ridebuddy.data.UnitFormatter
import com.spaceboy.ridebuddy.domain.BikeConnectionState
import com.spaceboy.ridebuddy.domain.BikeControlEvent
import java.util.Locale
import com.spaceboy.ridebuddy.service.NavInfoReceivingService
import com.spaceboy.ridebuddy.ui.theme.Rs457Theme
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.launch

/**
 * The turn-by-turn map screen.
 *
 * Its unusual property is that closing it does not stop navigation. Guidance keeps running
 * — the SDK holds its own foreground service, and the cluster keeps drawing turns — so
 * leaving this Activity detaches the UI rather than tearing the route down. That is the
 * normal riding case, with the phone stowed. See [shouldKeepGuidanceInBackground], and
 * [NavigationStopController] for the path that actually stops guidance.
 *
 * Every instance takes a session id and everything it does is guarded by
 * [NavigationSessionOwnership]. The navigator arrives asynchronously and there is exactly
 * one per process, so a recreated Activity can easily be handed a navigator that a newer
 * instance already owns; without the ownership check, its cleanup would tear down the live
 * route.
 */
class NavigationActivity : ComponentActivity() {
    private lateinit var navigationView: NavigationView
    private var statusTextState = mutableStateOf("")
    private var retryVisibleState = mutableStateOf(false)
    private var navigator: Navigator? = null
    private var guidanceStarted by mutableStateOf(false)
    private val stagingVisibleState = mutableStateOf(true)
    private var previewPanelHeight = 0
    private val routeReadyState = mutableStateOf(false)
    private val routeSummaryState = mutableStateOf("")
    private var routeRequestGeneration = 0L
    private var connectionSessionSeen = false
    private val navigationSessionId = NextNavigationSessionId.incrementAndGet()

    /**
     * Updates this screen only. The cluster output and the hazard alert are driven from the
     * process-scoped guidance handler in [com.spaceboy.ridebuddy.AppContainer], so they keep
     * working while the map is closed — and so the alert is raised before the reroute frames are
     * queued, which a second caller here could not guarantee.
     */
    private val reroutingListener = Navigator.ReroutingListener {
        if (!NavigationSessionOwners.isOwner(navigationSessionId)) return@ReroutingListener
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
                    if (stagingVisibleState.value && !retryVisibleState.value) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                            ElevatedCard(
                                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)
                                    .onSizeChanged { size ->
                                        if (previewPanelHeight != size.height) {
                                            previewPanelHeight = size.height
                                            if (routeReadyState.value) navigator?.let(::showWholeRoute)
                                        }
                                    },
                                shape = RoundedCornerShape(24.dp),
                            ) {
                                Column(
                                    Modifier.fillMaxWidth().padding(20.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Text(
                                        intent.getStringExtra(ExtraTitle) ?: "Route preview",
                                        style = MaterialTheme.typography.titleLarge,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        if (routeReadyState.value) routeSummaryState.value else statusTextState.value,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Button(
                                        onClick = ::startPreparedGuidance,
                                        enabled = routeReadyState.value,
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        if (routeReadyState.value) {
                                            Icon(Icons.Outlined.Navigation, contentDescription = null)
                                        } else {
                                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                        }
                                        Spacer(Modifier.width(8.dp))
                                        Text(if (routeReadyState.value) "Go" else "Finding route…")
                                    }
                                }
                            }
                        }
                    }
                    // Route errors keep the preview available for retry.
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
                                            if (!appContainer.navigationSdkGateway.isConfiguredInProcess) {
                                                awaitNavigationKeyAndInitialize()
                                            } else if (hasRequiredLocationPermissions()) {
                                                if (intent.getBooleanExtra(ExtraAttachExistingGuidance, false)) {
                                                    navigator?.takeUnless { it.isGuidanceRunning }?.let { currentNavigator ->
                                                        currentNavigator.removeReroutingListener(reroutingListener)
                                                        releaseNavigationSession(currentNavigator, stopGuidance = false)
                                                    }
                                                    navigator = null
                                                    initializeNavigation()
                                                } else {
                                                    navigator?.let { currentNavigator ->
                                                        calculateRoute(currentNavigator)
                                                    } ?: initializeNavigation()
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
        awaitNavigationKeyAndInitialize()

        lifecycleScope.launch {
            appContainer.bikeConnection.controls.collect { event ->
                if (!NavigationSessionOwners.isOwner(navigationSessionId)) return@collect
                when (event) {
                    // Closes this screen and nothing else. Stopping guidance belongs to the
                    // process-scoped handler in AppContainer, which owns the single stop path
                    // through NavigationStopController. Ending it here as well ran stopGuidance,
                    // cleanup and the display clear twice over the same navigator, from two
                    // threads, with only one of them behind the stop guard.
                    //
                    // finish() deliberately does not set navigationEndedByUser: that flag is what
                    // makes onDestroy tear the session down, and the whole point here is that it
                    // must not. onDestroy takes the detach-and-leave-running branch instead, and
                    // the stop controller retires the session a moment later.
                    BikeControlEvent.ExitNavigation -> {
                        routeRequestGeneration++
                        routeReadyState.value = false
                        finish()
                    }
                    BikeControlEvent.StartNavigation -> startPreparedGuidance()
                    BikeControlEvent.SkipManeuver -> {
                        val currentNavigator = navigator
                        if ((currentNavigator?.timeAndDistanceList?.size ?: 0) > 1) {
                            currentNavigator?.continueToNextDestination()
                        } else {
                            navigationView.showRouteOverview()
                        }
                    }
                    // Calls and cluster readiness are handled at process scope.
                    is BikeControlEvent.CallAction,
                    BikeControlEvent.ClusterReady,
                    BikeControlEvent.ClusterCallActive,
                    -> Unit
                }
            }
        }
        lifecycleScope.launch {
            appContainer.bikeConnection.connectionState.collect { state ->
                val terminal = state is BikeConnectionState.Failed ||
                    (state is BikeConnectionState.Disconnected && connectionSessionSeen)
                if (state !is BikeConnectionState.Disconnected) connectionSessionSeen = true
                if (terminal) {
                    routeRequestGeneration++
                    routeReadyState.value = false
                    finish()
                }
            }
        }
    }

    /**
     * Waits for the process-wide key load before touching the SDK. Reports whichever error
     * explains the failure — the load's exception, its recorded message, or a fallback.
     */
    private fun awaitNavigationKeyAndInitialize() {
        lifecycleScope.launch {
            val result = appContainer.navigationKeyBootstrap.await()
            if (isFinishing || isDestroyed) return@launch
            if (appContainer.navigationSdkGateway.isConfiguredInProcess) {
                requestLocationOrInitialize()
                return@launch
            }
            val message = result.exceptionOrNull()?.message
                ?: result.getOrNull()?.errorMessage
                ?: "Navigation API key is not configured"
            showError(message)
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

    /**
     * Fetches the navigator and starts or attaches to a route.
     *
     * The callback can arrive after this Activity is gone. When it does, the navigator is
     * cleaned up rather than leaked — but only if no newer instance has claimed it, and only
     * when guidance is not running or the rider explicitly ended it.
     */
    private fun initializeNavigation() {
        runCatching {
            NavigationApi.getNavigator(this, object : NavigationApi.NavigatorListener {
                override fun onNavigatorReady(readyNavigator: Navigator) {
                    if (isFinishing || isDestroyed) {
                        if (!readyNavigator.isGuidanceRunning &&
                            // Cleanup path: only release if no live session has taken ownership.
                            NavigationSessionOwners.claim(navigationSessionId)
                        ) {
                            releaseNavigationSession(readyNavigator, stopGuidance = false)
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
                            stagingVisibleState.value = false
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
                stagingVisibleState.value = false
                readyNavigator.removeReroutingListener(reroutingListener)
                runOnUiThread { statusTextState.value = getString(R.string.navigation_arrived) }
            },
        )
        if (!attached) {
            readyNavigator.removeReroutingListener(reroutingListener)
        }
        return attached
    }

    private fun prepareNewRoute(readyNavigator: Navigator) {
        runCatching(readyNavigator::stopGuidance)
        runCatching(readyNavigator::unregisterServiceForNavUpdates)
        guidanceStarted = false
        appContainer.navigationFeed.clear()
        appContainer.tftNavigationBridge.stop()
        navigationView.isNavigationUiEnabled = false
        calculateRoute(readyNavigator)
    }

    private fun calculateRoute(navigator: Navigator) {
        val requestGeneration = ++routeRequestGeneration
        stagingVisibleState.value = true
        routeReadyState.value = false
        retryVisibleState.value = false
        statusTextState.value = getString(R.string.navigation_preparing_route)
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
                    requestGeneration != routeRequestGeneration ||
                    !NavigationSessionOwners.isOwner(navigationSessionId) || this.navigator !== navigator
                ) return@runOnUiThread
                if (status == Navigator.RouteStatus.OK) {
                    routeReadyState.value = true
                    val trip = navigator.currentTimeAndDistance
                    val distance = trip?.let {
                        UnitFormatter.distance(it.meters / 1_000.0, preferences.distanceUnits, Locale.getDefault())
                    }
                    val minutes = trip?.seconds?.let { (it.coerceAtLeast(0).toLong() + 59) / 60 }
                    routeSummaryState.value = listOfNotNull(
                        distance,
                        minutes?.let { if (it < 60) "$it min" else "${it / 60} hr ${it % 60} min" },
                    ).joinToString(" • ").ifBlank { "Route ready from your current location" }
                    if (intent.getBooleanExtra(ExtraAutoStartGuidance, false)) {
                        startPreparedGuidance()
                    } else {
                        appContainer.tftNavigationBridge.previewDestination(
                            intent.getStringExtra(ExtraTitle).orEmpty(),
                            destinationDistanceMetres = trip?.meters,
                            timeToDestinationSeconds = trip?.seconds,
                        )
                        showWholeRoute(navigator)
                    }
                } else {
                    clearNavigationOutput()
                    showError("Route unavailable: ${status.name.replace('_', ' ').lowercase()}")
                }
            }
        }
    }

    /** Both the phone Go button and handlebar GO enter this same, idempotent start path. */
    private fun startPreparedGuidance() {
        val currentNavigator = navigator ?: return
        if (!routeReadyState.value || guidanceStarted || isFinishing || isDestroyed ||
            !NavigationSessionOwners.isOwner(navigationSessionId)
        ) return
        routeReadyState.value = false
        val options = NavigationUpdatesOptions.builder().setNumNextStepsToPreview(1).build()
        val tftUpdatesRegistered = runCatching {
            currentNavigator.registerServiceForNavUpdates(
                packageName, NavInfoReceivingService::class.java.name, options,
            )
        }.getOrDefault(false)
        if (!tftUpdatesRegistered) {
            showError(getString(R.string.navigation_tft_updates_unavailable))
            clearNavigationOutput()
            return
        }
        appContainer.tftNavigationBridge.start(intent.getStringExtra(ExtraTitle).orEmpty())
        runCatching(currentNavigator::startGuidance).onSuccess {
            stagingVisibleState.value = false
            guidanceStarted = true
            appContainer.navigationGuidanceLifecycle.markGuidanceStarted(navigationSessionId)
            navigationView.isNavigationUiEnabled = true
            navigationView.getMapAsync { map ->
                if (guidanceStarted && NavigationSessionOwners.isOwner(navigationSessionId)) {
                    map.setPadding(0, 0, 0, 0)
                    // Checked inline rather than through hasRequiredLocationPermissions(), which
                    // lint cannot follow. A revoked grant leaves the map where it is instead of
                    // throwing SecurityException out of a Maps callback.
                    if (ContextCompat.checkSelfPermission(
                            this,
                            Manifest.permission.ACCESS_FINE_LOCATION,
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        map.followMyLocation(GoogleMap.CameraPerspective.TILTED)
                    }
                }
            }
            statusTextState.value = getString(R.string.navigation_active)
        }.onFailure {
            runCatching(currentNavigator::unregisterServiceForNavUpdates)
            clearNavigationOutput()
            showError(getString(R.string.navigation_start_failed_unknown))
        }
    }

    /** SDK showRouteOverview stops at 45 minutes; fit every segment for a full-trip preview. */
    private fun showWholeRoute(currentNavigator: Navigator) {
        val points = currentNavigator.routeSegments.flatMap { it.latLngs }
        if (points.isEmpty()) return
        val bounds = LatLngBounds.builder().apply { points.forEach(::include) }.build()
        navigationView.doOnLayout {
            navigationView.getMapAsync { map ->
                if (!routeReadyState.value || !NavigationSessionOwners.isOwner(navigationSessionId)) return@getMapAsync
                val density = resources.displayMetrics.density
                map.setPadding(0, (24 * density).toInt(), 0, previewPanelHeight + (32 * density).toInt())
                map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, (32 * density).toInt()))
            }
        }
    }

    private fun showError(message: String) {
        routeReadyState.value = false
        statusTextState.value = message
        retryVisibleState.value = true
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

    /**
     * Decides between tearing the route down and leaving it running in the background.
     *
     * Backgrounding detaches the UI and releases ownership so a future instance can claim
     * the navigator; only an explicit end, or a session that never got started, tears it
     * down.
     */
    override fun onDestroy() {
        routeRequestGeneration++
        routeReadyState.value = false
        val currentNavigator = navigator
        currentNavigator?.removeReroutingListener(reroutingListener)
        val continueInBackground = shouldKeepGuidanceInBackground(
            guidanceStarted = guidanceStarted,
            guidanceIsRunning = currentNavigator?.isGuidanceRunning == true,
        )
        if (!continueInBackground) {
            if (currentNavigator != null) {
                releaseNavigationSession(currentNavigator, stopGuidance = false)
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

    /**
     * Tears down this session's navigator, but only if this instance still owns it —
     * otherwise a newer instance has taken over and cleaning up would kill its live route.
     */
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
        private const val ExtraAutoStartGuidance = "auto_start_guidance"
        private const val ExtraAttachExistingGuidance = "attach_existing_guidance"
        private const val KeyGuidanceStarted = "guidance_started"
        private val NextNavigationSessionId = AtomicLong()
        private val NavigationSessionOwners = NavigationSessionOwnership()
        private val LocationPermissions = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )

        fun intent(
            context: Context, latitude: Double, longitude: Double, title: String,
            autoStartGuidance: Boolean = false,
        ): Intent =
            Intent(context, NavigationActivity::class.java)
                .putExtra(ExtraLatitude, latitude)
                .putExtra(ExtraLongitude, longitude)
                .putExtra(ExtraTitle, title)
                .putExtra(ExtraAutoStartGuidance, autoStartGuidance)

        fun activeGuidanceIntent(context: Context): Intent =
            Intent(context, NavigationActivity::class.java)
                .putExtra(ExtraAttachExistingGuidance, true)
    }
}

/** What a launch of this Activity should do about guidance. */
internal enum class NavigationLaunchPolicy {
    /** Guidance is already running; show it rather than restarting it. */
    AttachExisting,

    PrepareNewRoute,

    /** Asked to attach, but nothing is running — the route ended while the app was away. */
    NoActiveRoute,
}

/**
 * Chooses the launch behaviour.
 *
 * `guidanceWasStarted` comes from saved state and covers a recreated Activity whose route
 * is still running; `attachRequested` covers the rider reopening the map deliberately.
 * Either one attaches, but only when guidance is genuinely still running.
 */
internal fun navigationLaunchPolicy(
    attachRequested: Boolean,
    guidanceWasStarted: Boolean,
    guidanceIsRunning: Boolean,
): NavigationLaunchPolicy = when {
    guidanceIsRunning && (attachRequested || guidanceWasStarted) -> NavigationLaunchPolicy.AttachExisting
    attachRequested -> NavigationLaunchPolicy.NoActiveRoute
    else -> NavigationLaunchPolicy.PrepareNewRoute
}

/**
 * Whether guidance should survive this Activity being destroyed. A rotation, a back press, the
 * app being backgrounded, and the handlebar EXIT all keep the route: stopping is the
 * process-scoped handler's job, never this screen's.
 */
internal fun shouldKeepGuidanceInBackground(
    guidanceStarted: Boolean,
    guidanceIsRunning: Boolean,
): Boolean = guidanceStarted || guidanceIsRunning

/**
 * Decides which Activity instance owns the process's single navigator.
 *
 * Two ideas, deliberately separate. *Newest* is the most recently created instance and is
 * the only one allowed to claim ownership — an older instance's late callback must not.
 * *Owner* is whoever currently holds the navigator, and only the owner may release it,
 * which is what stops a departing instance from cleaning up a navigator that a newer one is
 * driving.
 */
internal class NavigationSessionOwnership {
    private var newestSessionId: Long? = null
    private var ownerId: Long? = null

    /** Announces a new instance. Ids increase, so a stale registration cannot displace it. */
    @Synchronized
    fun register(sessionId: Long) {
        if (sessionId > (newestSessionId ?: Long.MIN_VALUE)) {
            newestSessionId = sessionId
            ownerId = null
        }
    }

    /** Takes ownership. Fails for a superseded session, or when someone already holds it. */
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

    /** Gives up ownership. False when this session was not the owner — nothing to release. */
    @Synchronized
    fun release(sessionId: Long): Boolean {
        if (ownerId != sessionId) return false
        ownerId = null
        return true
    }
}
