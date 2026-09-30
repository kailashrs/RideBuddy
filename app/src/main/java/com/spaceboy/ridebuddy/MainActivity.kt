package com.spaceboy.ridebuddy

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.spaceboy.ridebuddy.ble.shouldAutoConnectOnLaunch
import com.spaceboy.ridebuddy.core.companion.BikeAssociationState
import com.spaceboy.ridebuddy.core.diagnostics.diagnosticsReport
import com.spaceboy.ridebuddy.core.tft.StationaryTftSafetyReason
import com.spaceboy.ridebuddy.core.tft.StationaryTftPhase
import com.spaceboy.ridebuddy.core.tft.StationaryTftTestResult
import com.spaceboy.ridebuddy.data.AppSettings
import com.spaceboy.ridebuddy.data.toCsv
import com.spaceboy.ridebuddy.domain.BikeConnectionState
import com.spaceboy.ridebuddy.domain.ConnectionAttemptTrigger
import com.spaceboy.ridebuddy.service.BikeConnectionService
import com.spaceboy.ridebuddy.ui.MainScreen
import com.spaceboy.ridebuddy.ui.MainScreenActions
import com.spaceboy.ridebuddy.ui.LiveTelemetryStreams
import com.spaceboy.ridebuddy.ui.MainScreenContent
import com.spaceboy.ridebuddy.ui.MainScreenState
import com.spaceboy.ridebuddy.ui.OnboardingScreen
import com.spaceboy.ridebuddy.ui.labelResource
import com.spaceboy.ridebuddy.ui.screens.MoreSettingsActions
import com.spaceboy.ridebuddy.ui.theme.Rs457Theme
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Hosts onboarding, the main Compose screens, permission settings and exports.
 * Navigation and ride details have their own Activities.
 *
 * Feature state belongs to [MainViewModel] and [AppContainer]. This Activity owns platform
 * interactions and permission UI state; [BikeConnectionActions] handles the shared pairing flow.
 *
 * Permission state is mirrored into Compose-observable fields and re-read in [onResume],
 * because the rider can grant or revoke any of it in system settings while the app is in
 * the background, and none of that produces a callback.
 */
class MainActivity : ComponentActivity() {
    private var notificationAccessEnabled by mutableStateOf(false)
    private var appNotificationPermissionGranted by mutableStateOf(false)
    private var nearbyDeviceAccessGranted by mutableStateOf(false)
    private var preciseLocationGranted by mutableStateOf(false)
    private var backgroundLocationGranted by mutableStateOf(false)
    private var navigationStartJob: Job? = null
    private val navigationStartStopGuard: NavigationStartStopGuard
        get() = appContainer.navigationStartStopGuard
    private val viewModel: MainViewModel by viewModels {
        MainViewModel.factory(appContainer)
    }

    // Result launchers must be registered before the Activity is started, so they are all
    // declared as fields rather than created where they are used.

    private val bikeConnectionActions = BikeConnectionActions(
        activity = this,
        onMessage = { viewModel.showMessage(it) },
        onPermissionResult = ::refreshRuntimePermissionState,
    )

    private val onboardingNearbyPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        refreshRuntimePermissionState()
        viewModel.showMessage(if (nearbyDeviceAccessGranted) "Bluetooth access ready" else "Allow Bluetooth to connect.")
    }

    private val onboardingLocationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        refreshRuntimePermissionState()
        if (preciseLocationGranted && viewModel.connectionState.value !is BikeConnectionState.Disconnected) {
            BikeConnectionService.enableLocation(this)
        }
        viewModel.showMessage(
            if (preciseLocationGranted) "Precise location granted" else "Route recording needs precise location",
        )
    }

    /** Contacts are what turn a caller's number into their name on the cluster. */
    private val contactsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    private val appNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        appNotificationPermissionGranted = granted
        viewModel.showMessage(if (granted) "Riding alerts enabled" else "Phone-side riding alerts remain disabled")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIncomingIntent(intent)

        setContent {
            val uiState = viewModel.uiState.collectAsStateWithLifecycle().value
            val settings = viewModel.settings.collectAsStateWithLifecycle().value
            val autoStartSharedDestination = uiState.autoStartSharedDestination
            LaunchedEffect(autoStartSharedDestination?.requestId, uiState.navigationKey.isLoading) {
                if (!uiState.navigationKey.isLoading) autoStartSharedDestination?.let { request ->
                    lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                        startNavigation(request.destination, request.requestId)
                    }
                }
            }
            val bikeAssociation = appContainer.bikeCompanionManager.state
                .collectAsStateWithLifecycle().value
            val settingsActions = remember {
                MoreSettingsActions(
                    onNotificationPackageChanged = viewModel::setNotificationPackageEnabled,
                    onCallerDisplayChanged = { enabled ->
                        viewModel.setCallerDisplay(enabled)
                        if (enabled && checkSelfPermission(Manifest.permission.READ_CONTACTS) !=
                            PackageManager.PERMISSION_GRANTED
                        ) {
                            contactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
                        }
                    },
                    onTftCallControlsChanged = viewModel::setTftCallControls,
                    onRideStartSpeedChanged = viewModel::setRideStartSpeed,
                    onRideStopSpeedChanged = viewModel::setRideStopSpeed,
                    onRideStopDelayChanged = viewModel::setRideStopDelay,
                    onOverspeedAlertsChanged = viewModel::setOverspeedAlerts,
                    onOverspeedThresholdChanged = viewModel::setOverspeedThreshold,
                    onRpmAlertsChanged = viewModel::setRpmAlerts,
                    onRpmThresholdChanged = viewModel::setRpmThreshold,
                    onAccelerationAlertsChanged = viewModel::setAccelerationAlerts,
                    onBrakingAlertsChanged = viewModel::setBrakingAlerts,
                    onWeatherAlertsChanged = viewModel::setWeatherAlerts,
                    onHazardAlertsChanged = viewModel::setHazardAlerts,
                    onTftNavigationOutputChanged = viewModel::setTftNavigationOutput,
                    onTftTextModeChanged = viewModel::setTftTextMode,
                    onSampleRetentionChanged = viewModel::setSampleRetention,
                    onThemeModeChanged = viewModel::setThemeMode,
                    onDynamicColorChanged = viewModel::setDynamicColor,
                    onHighContrastChanged = viewModel::setHighContrast,
                    onBleCaptureEnabledChanged = viewModel::setBleCaptureEnabled,
                    onPersistConnectionDiagnosticsChanged = viewModel::setPersistConnectionDiagnostics,
                )
            }
            val mainScreenActions = remember(settingsActions) { createMainScreenActions(settingsActions) }
            LaunchedEffect(Unit) { maybeAutoConnect() }
            Rs457Theme(
                themeMode = settings.themeMode,
                dynamicColor = settings.dynamicColor,
                highContrast = settings.highContrast,
            ) {
                if (!settings.onboardingComplete) {
                    OnboardingRoute(
                        uiState = uiState,
                        bikeAssociation = bikeAssociation,
                    )
                } else {
                    MainScreenRoute(uiState, settings, bikeAssociation, mainScreenActions)
                }
                uiState.tftTestConfirmation?.let { message ->
                    TftTestConfirmationDialog(
                        message = message,
                        onResolved = viewModel::resolveTftTestConfirmation,
                    )
                }
            }
        }
    }

    @Composable
    private fun OnboardingRoute(uiState: MainUiState, bikeAssociation: BikeAssociationState) {
        val connectionState = viewModel.connectionState.collectAsStateWithLifecycle().value
        // The checklist needs two booleans, not the frames themselves. Collecting the frames would
        // re-compose the whole checklist four times a second while the bike is connected.
        val telemetryReceiving by remember(viewModel) {
            viewModel.telemetry.map { frame -> frame != null }.distinctUntilChanged()
        }.collectAsStateWithLifecycle(false)
        val authenticated by remember(viewModel) {
            viewModel.diagnostics.map { it.authenticated }.distinctUntilChanged()
        }.collectAsStateWithLifecycle(false)
        OnboardingScreen(
            connectionState = connectionState,
            bikeAssociated = bikeAssociation.bike != null,
            nearbyDeviceAccessGranted = nearbyDeviceAccessGranted,
            preciseLocationGranted = preciseLocationGranted,
            notificationAccessEnabled = notificationAccessEnabled,
            appNotificationPermissionGranted = appNotificationPermissionGranted,
            telemetryReceiving = telemetryReceiving,
            authenticated = authenticated,
            navigationConfigured = uiState.navigationKey.isConfigured,
            onRequestNearbyDeviceAccess = ::requestOnboardingNearbyDeviceAccess,
            onRequestPreciseLocation = { onboardingLocationPermissionLauncher.launch(LocationPermissions) },
            onAssociateBike = bikeConnectionActions::requestConnection,
            onOpenNotificationAccess = ::openNotificationAccessSettings,
            onRequestAppNotificationPermission = ::requestAppNotificationPermission,
            onSetUpNavigation = {
                viewModel.completeOnboarding()
                viewModel.openNavigationSettings()
            },
            onComplete = viewModel::completeOnboarding,
        )
    }

    @Composable
    private fun MainScreenRoute(
        uiState: MainUiState,
        settings: AppSettings,
        bikeAssociation: BikeAssociationState,
        actions: MainScreenActions,
    ) {
        MainScreen(uiState = uiState, actions = actions) { modifier ->
            MainScreenContentRoute(modifier, uiState, settings, bikeAssociation, actions)
        }
    }

    @Composable
    private fun MainScreenContentRoute(
        modifier: Modifier,
        uiState: MainUiState,
        settings: AppSettings,
        bikeAssociation: BikeAssociationState,
        actions: MainScreenActions,
    ) {
        val live = remember(viewModel) {
            LiveTelemetryStreams(
                telemetry = viewModel.telemetry,
                diagnostics = viewModel.diagnostics,
                activeRide = viewModel.activeRide,
                rideSamples = viewModel.liveRideSamples,
                rideMetrics = viewModel.liveRideMetrics,
                accelerationG = viewModel.accelerationG,
            )
        }
        MainScreenContent(
            modifier = modifier,
            state = MainScreenState(
                uiState = uiState,
                connectionState = viewModel.connectionState.collectAsStateWithLifecycle().value,
                identity = viewModel.identity.collectAsStateWithLifecycle().value,
                bleCapture = viewModel.bleCapture.collectAsStateWithLifecycle().value,
                live = live,
                rides = viewModel.rides.collectAsStateWithLifecycle().value,
                insights = viewModel.insights.collectAsStateWithLifecycle().value,
                insightPeriod = viewModel.selectedInsightPeriod.collectAsStateWithLifecycle().value,
                guidance = viewModel.guidance.collectAsStateWithLifecycle().value,
                settings = settings,
                bikeAssociation = bikeAssociation,
                notificationAccessEnabled = notificationAccessEnabled,
                backgroundLocationGranted = backgroundLocationGranted,
            ),
            actions = actions,
        )
    }

    private fun createMainScreenActions(settingsActions: MoreSettingsActions) = MainScreenActions(
        onDestinationSelected = viewModel::selectDestination,
        onOpenNavigationSettings = viewModel::openNavigationSettings,
        onCloseNavigationSettings = viewModel::closeNavigationSettings,
        onOpenDiagnostics = viewModel::openDiagnostics,
        onCloseDiagnostics = viewModel::closeDiagnostics,
        onSaveNavigationApiKey = viewModel::saveNavigationApiKey,
        onRemoveNavigationApiKey = viewModel::removeNavigationApiKey,
        onTestNavigationApiKey = viewModel::testNavigationApiKey,
        onDisconnectBike = { BikeConnectionService.disconnect(this) },
        onEndRide = { BikeConnectionService.endRide(this) },
        onStartNavigation = ::startNavigation,
        onOpenActiveNavigation = ::openActiveNavigation,
        onStopNavigation = ::stopNavigation,
        onSharedDestinationHandled = {
            navigationStartJob?.cancel()
            viewModel.clearSharedDestination()
        },
        onCancelNavigationStart = ::cancelNavigationStart,
        onInsightPeriodSelected = viewModel::selectInsightPeriod,
        onClearRideHistory = viewModel::clearRideHistory,
        onExportRideHistory = ::exportRideHistory,
        onOpenNotificationAccess = ::openNotificationAccessSettings,
        onAssociateBike = bikeConnectionActions::requestConnection,
        onForgetBike = ::forgetBike,
        onRideSelected = { ride -> startActivity(RideDetailActivity.intent(this, ride.id)) },
        onDistanceUnitsChanged = viewModel::setDistanceUnits,
        onVoiceGuidanceChanged = viewModel::setVoiceGuidance,
        onAvoidTollsChanged = viewModel::setAvoidTolls,
        onAvoidHighwaysChanged = viewModel::setAvoidHighways,
        onAvoidFerriesChanged = viewModel::setAvoidFerries,
        onAutoStartSharedChanged = viewModel::setAutoStartSharedDestinations,
        settingsActions = settingsActions,
        onResetOnboarding = viewModel::resetOnboarding,
        onExportDiagnostics = ::exportDiagnostics,
        onExportBleCapture = ::exportBleCapture,
        onClearBleCapture = viewModel::clearBleCapture,
        onRunStationaryTest = ::runStationaryTest,
        onOpenAppPermissions = ::openAppPermissionSettings,
        onMessageShown = viewModel::clearTransientMessage,
    )

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    /**
     * Re-reads everything the rider can change outside the app: notification access, runtime
     * permissions, and the companion association. None of these notify on change, so a
     * resume is the only reliable point to reconcile them.
     */
    override fun onResume() {
        super.onResume()
        notificationAccessEnabled = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
        refreshRuntimePermissionState()
        backgroundLocationGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val container = appContainer
        // CDM Binder IPCs are not safe to run inline on Main: each call costs
        // ~10-50ms on cold-cache devices and onResume runs on every resume.
        // lifecycleScope cancels the work when the activity is destroyed, so the
        // late state update never lands on a dead view tree.
        lifecycleScope.launch(Dispatchers.IO) {
            // Reconciles what the system knows against what the app stored — the rider can remove
            // the association from system settings while the app is backgrounded. Registration
            // itself is not repeated here: the manager does it once at startup and again when an
            // association is accepted, and re-registering on every resume only made it harder to
            // see which call was the one that mattered.
            container.bikeCompanionManager.refresh()
        }
        if (viewModel.connectionState.value !is BikeConnectionState.Disconnected &&
            viewModel.connectionState.value !is BikeConnectionState.Failed
        ) {
            BikeConnectionService.enableLocation(this)
        }
    }

    /** Mirrors the current runtime-permission grants into the Compose-observable fields. */
    private fun refreshRuntimePermissionState() {
        nearbyDeviceAccessGranted = requiredNearbyDevicePermissions().all { permission ->
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }
        preciseLocationGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        appNotificationPermissionGranted =
            ContextCompat.checkSelfPermission(this, NotificationPermission) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestOnboardingNearbyDeviceAccess() {
        onboardingNearbyPermissionLauncher.launch(requiredNearbyDevicePermissions())
    }

    private fun requestAppNotificationPermission() {
        appNotificationPermissionLauncher.launch(NotificationPermission)
    }

    private fun forgetBike() {
        BikeConnectionService.disconnect(this)
        val manager = appContainer.bikeCompanionManager
        if (manager.forget()) {
            viewModel.showMessage("Bike association removed")
        } else {
            viewModel.showMessage(manager.state.value.errorMessage ?: "Could not remove the motorcycle association")
        }
    }

    private fun openAppPermissionSettings() {
        launchExternalActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri()),
            R.string.settings_unavailable,
        )
    }

    private fun openNotificationAccessSettings() {
        launchExternalActivity(
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
            R.string.settings_unavailable,
        )
    }

    private fun launchExternalActivity(intent: Intent, failureMessageRes: Int) {
        runCatching { startActivity(intent) }
            .onFailure { viewModel.showMessage(getString(failureMessageRes)) }
    }

    /** Handles one-shot launch commands before dispatching ordinary share intents. */
    private fun handleIncomingIntent(intent: Intent?) {
        handleShareIntent(intent)
    }

    /**
     * Handles a destination shared from another app.
     *
     * The intent is consumed *before* validation, by replacing it with a blank one. A share
     * intent stays attached to the Activity across recreation, so leaving it in place would
     * mean re-processing the same share — and, for input that fails validation, re-showing
     * the same error on every rotation.
     */
    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND || intent.type != "text/plain") return
        val destination = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()

        // ACTION_SEND remains attached to a recreated Activity unless it is explicitly consumed.
        // Consume it before validation so malformed input is not retried after recreation.
        setIntent(Intent(this, MainActivity::class.java))
        if (destination.isEmpty()) return
        if (destination.length > MaxDestinationInputLength) {
            viewModel.showMessage(getString(R.string.destination_too_long))
            return
        }
        navigationStartJob?.cancel()
        // Always queued, never parked in the field behind a confirmation sheet: the route
        // preview is now the prompt, so a second one before it would ask twice. The setting
        // decides whether that preview is skipped too, at the launch below.
        viewModel.queueAutoStartSharedDestination(destination)
    }

    private fun requiredNearbyDevicePermissions(): Array<String> =
        arrayOf(Manifest.permission.BLUETOOTH_CONNECT)

    /** Resolves the link before opening the route preview. */
    private fun startNavigation(rawDestination: String) {
        viewModel.uiState.value.autoStartSharedDestination
            ?.requestId
            ?.let(viewModel::completeAutoStartSharedDestination)
        lifecycleScope.launch { startNavigation(rawDestination, autoStartRequestId = null) }
    }

    /**
     * The single navigation-start path. A newer request always cancels the one in flight, and an
     * auto-start request that has been superseded or consumed elsewhere is abandoned silently.
     */
    private suspend fun startNavigation(rawDestination: String, autoStartRequestId: Long?) {
        val currentJob = currentCoroutineContext().job
        navigationStartJob?.takeIf { it !== currentJob }?.cancel()
        navigationStartJob = currentJob

        fun autoStartSuperseded(): Boolean = autoStartRequestId != null &&
            viewModel.uiState.value.autoStartSharedDestination?.requestId != autoStartRequestId

        fun abandon(errorMessage: String? = null) {
            autoStartRequestId?.let { viewModel.restoreAutoStartSharedDestination(it, errorMessage) }
        }

        var navigationStartAttemptId: Long? = null
        try {
            // The field and share handler constrain input; the parser reports invalid links.
            val destination = rawDestination.trim()
            if (autoStartSuperseded()) return
            navigationStartAttemptId = viewModel.beginNavigationStart()

            if (viewModel.uiState.value.navigationKey.isLoading) {
                abandon()
                viewModel.showMessage("Navigation setup is still loading")
                return
            }
            if (!viewModel.uiState.value.navigationKey.isConfigured) {
                abandon()
                viewModel.openNavigationSettings()
                viewModel.showMessage("Add a Google Navigation key to start a route.")
                return
            }

            val parsed = appContainer.destinationParser.parse(destination)
            if (autoStartSuperseded()) return
            parsed.fold(
                onSuccess = { place ->
                    startActivity(
                        NavigationActivity.intent(
                            this, place.latitude, place.longitude, place.title,
                            // A share only skips the preview when the rider opted into that;
                            // otherwise it still stops on the preview for a Go.
                            autoStartGuidance = autoStartRequestId != null &&
                                viewModel.settings.value.autoStartSharedDestinations,
                        ),
                    )
                    navigationStartStopGuard.beginStart()
                    autoStartRequestId?.let(viewModel::completeAutoStartSharedDestination)
                    viewModel.clearSharedDestination()
                },
                onFailure = { error ->
                    val message = error.message ?: "Could not read that destination"
                    abandon(message)
                    viewModel.showMessage(message)
                },
            )
        } finally {
            navigationStartAttemptId?.let(viewModel::finishNavigationStart)
            if (navigationStartJob === currentJob) navigationStartJob = null
        }
    }

    /**
     * Backs out of a route lookup. An auto-started share is handed back to the destination field
     * rather than discarded, so the rider still has what they shared.
     */
    private fun cancelNavigationStart() {
        navigationStartJob?.cancel()
        viewModel.uiState.value.autoStartSharedDestination
            ?.requestId
            ?.let { requestId -> viewModel.restoreAutoStartSharedDestination(requestId) }
    }

    /** Brings the map back for guidance that is already running in the background. */
    private fun openActiveNavigation() {
        runCatching {
            startActivity(NavigationActivity.activeGuidanceIntent(this))
        }.onFailure {
            viewModel.showMessage(getString(R.string.navigation_map_unavailable))
        }
    }

    /**
     * Delegates to the process-scoped controller so the button and the handlebar EXIT take the
     * same path; only the rider-facing message is added here.
     */
    private fun stopNavigation() {
        navigationStartJob?.cancel()
        appContainer.navigationStopController.stop { result ->
            when (result) {
                NavigationStopResult.Stopped,
                NavigationStopResult.AlreadyStopping,
                -> Unit

                NavigationStopResult.CleanupIncomplete ->
                    appContainer.connectionEventJournal.record(
                        "Navigation ended; Google Navigation cleanup was incomplete",
                    )

                NavigationStopResult.Failed ->
                    viewModel.showMessage(getString(R.string.navigation_end_request_failed))
            }
        }
    }

    private fun exportDiagnostics() {
        val diagnostics = viewModel.diagnostics.value
        val report = diagnosticsReport(
            connectionState = viewModel.connectionState.value,
            diagnostics = diagnostics,
            identity = viewModel.identity.value,
            protectionPhaseLabel = getString(diagnostics.protectionPhase.labelResource()),
            protectionPathLabel = diagnostics.protectionPath?.let { getString(it.labelResource()) },
        )
        launchExternalActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, "RideBuddy diagnostics")
                    .putExtra(Intent.EXTRA_TEXT, report),
                "Share diagnostics",
            ),
            R.string.diagnostics_share_unavailable,
        )
    }

    private fun exportBleCapture() {
        val report = appContainer.bleCaptureRecorder.exportText()
        launchExternalActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, "RideBuddy BLE capture")
                    .putExtra(Intent.EXTRA_TEXT, report),
                "Share BLE capture",
            ),
            R.string.ble_capture_share_unavailable,
        )
    }

    private fun exportRideHistory() {
        val csv = viewModel.rides.value.toCsv()
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) {
                runCatching {
                    val exportDir = File(cacheDir, "exports")
                    exportDir.mkdirs()
                    File(exportDir, "ride_history.csv").apply { writeText(csv) }
                }
            }.getOrNull()
            if (file == null) {
                viewModel.showMessage("Could not export ride history")
                return@launch
            }
            val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.fileprovider", file)
            launchExternalActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND)
                        .setType("text/csv")
                        .putExtra(Intent.EXTRA_SUBJECT, "RideBuddy ride history")
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                    "Export ride history",
                ),
                R.string.ride_history_share_unavailable,
            )
        }
    }

    /**
     * Runs the parked display validation: both phases in one pass, asking the rider to
     * confirm each as it happens.
     *
     * Gated on a verified link, since every phase is a sequence of acknowledged writes that
     * would simply fail without one.
     */
    private fun runStationaryTest() {
        val container = appContainer
        if (viewModel.connectionState.value !is BikeConnectionState.Connected || !viewModel.diagnostics.value.authenticated) {
            viewModel.showMessage("Connect to the bike before the display test.")
            return
        }
        lifecycleScope.launch {
            // A phase that looks wrong does not stop the run. The two surfaces are independent,
            // and knowing whether the second one also failed is what separates a broken link from
            // a broken pictogram — asking for it later would mean parking up a second time.
            val faults = StationaryTftPhase.entries.mapNotNull { phase ->
                if (!runStationaryPhase(phase)) phase else null
            }
            viewModel.showMessage(
                if (faults.isEmpty()) {
                    "TFT display test completed"
                } else {
                    "Needs investigation: " + faults.joinToString { phase ->
                        when (phase) {
                            StationaryTftPhase.Navigation -> "navigation display"
                            StationaryTftPhase.Calls -> "caller display"
                            StationaryTftPhase.Notifications -> "notification icons"
                        }
                    }
                },
            )
        }
    }

    /** Runs one phase and asks the rider about it. Returns false if it did not look right. */
    private suspend fun runStationaryPhase(phase: StationaryTftPhase): Boolean {
        val container = appContainer
        when (val result = container.stationaryTftValidator.run(phase)) {
            is StationaryTftTestResult.Failed -> {
                viewModel.showMessage("TFT test stopped after ${result.completedWrites} acknowledged writes")
                return false
            }
            is StationaryTftTestResult.SafetyStopped -> {
                val reason = when (result.reason) {
                    StationaryTftSafetyReason.Disconnected -> "the bike disconnected"
                    StationaryTftSafetyReason.NotAuthenticated -> "the motorcycle companion link was not verified"
                    StationaryTftSafetyReason.TelemetryUnavailable -> "live telemetry was unavailable"
                    StationaryTftSafetyReason.TelemetryStale -> "live telemetry became stale"
                    StationaryTftSafetyReason.BikeMoving -> "the bike started moving"
                }
                viewModel.showMessage(
                    "TFT test stopped after ${result.completedWrites} acknowledged writes because $reason",
                )
                return false
            }
            is StationaryTftTestResult.Succeeded -> {
                val latestTelemetry = container.bikeConnection.latestTelemetryReading.value
                if (latestTelemetry == null) {
                    viewModel.showMessage("TFT test result discarded because stationary telemetry was unavailable")
                    return false
                }
                if (latestTelemetry.frame.speedKilometresPerHour > 0.5) {
                    viewModel.showMessage("TFT test result discarded because the bike started moving")
                    return false
                }
                val prompt = when (phase) {
                    StationaryTftPhase.Navigation ->
                        "While parked, did you see the maneuver, distance, test text, speed " +
                            "limit, and clear state on the TFT?"

                    StationaryTftPhase.Calls ->
                        "While parked, did you see TEST CALLER ring, answer, clear, then show " +
                            "again as an outgoing call? The number should read 9876543210 — " +
                            "if it shows +919876543 the cluster is being sent too many digits."

                    StationaryTftPhase.Notifications ->
                        "While parked, did you see the Messages, Instagram and Gmail " +
                            "notification icons appear one after another, each for a few seconds?"
                }
                return viewModel.awaitTftTestConfirmation(
                    "The Bluetooth stack accepted ${result.acceptedWrites} test writes. $prompt",
                )
            }
        }
    }

    /**
     * The one automatic connection attempt an app launch is allowed.
     *
     * Every precondition is checked before the one-shot gate is consumed, and
     * [shouldAutoConnectOnLaunch] additionally refuses once the connection's own retry
     * budget is spent — so relaunching the app cannot be used to hand the stack a fresh one.
     */
    private fun maybeAutoConnect() {
        val settings = appContainer.appSettings.settings.value
        if (!settings.onboardingComplete) return
        if (!nearbyDeviceAccessGranted) return
        val bike = appContainer.bikeCompanionManager.state.value.bike ?: return
        if (!shouldAutoConnectOnLaunch(viewModel.connectionState.value)) return
        // Only burn the one-shot gate once every precondition is satisfied; otherwise
        // a cold start with missing permissions would permanently disable auto-connect.
        if (!viewModel.consumeAutoConnectAttempt()) return
        if (!BikeConnectionService.reconnect(
                this,
                bike,
                launchedFromVisibleActivity = true,
                trigger = ConnectionAttemptTrigger.AppLaunch,
            )
        ) {
            viewModel.showMessage("Couldn't start the connection. Try again.")
        }
    }

    @Composable
    private fun TftTestConfirmationDialog(message: String, onResolved: (Boolean) -> Unit) {
        AlertDialog(
            onDismissRequest = { onResolved(false) },
            title = { Text(stringResource(R.string.tft_test_confirm_title)) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { onResolved(true) }) {
                    Text(stringResource(R.string.tft_test_confirm_yes))
                }
            },
            dismissButton = {
                TextButton(onClick = { onResolved(false) }) {
                    Text(stringResource(R.string.tft_test_confirm_no))
                }
            },
        )
    }

    private companion object {
        const val NotificationPermission = "android.permission.POST_NOTIFICATIONS"
        val LocationPermissions = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
    }
}
