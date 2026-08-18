package com.spaceboy.ridebuddy

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.IntentSenderRequest
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.libraries.navigation.NavigationApi
import com.google.android.libraries.navigation.Navigator
import com.spaceboy.ridebuddy.core.companion.AssociatedBike
import com.spaceboy.ridebuddy.core.tft.StationaryTftSafetyReason
import com.spaceboy.ridebuddy.core.tft.StationaryTftTestResult
import com.spaceboy.ridebuddy.domain.BikeConnectionState
import com.spaceboy.ridebuddy.service.BikeConnectionService
import com.spaceboy.ridebuddy.ui.MainScreen
import com.spaceboy.ridebuddy.ui.MainScreenActions
import com.spaceboy.ridebuddy.ui.MainScreenState
import com.spaceboy.ridebuddy.ui.OnboardingScreen
import com.spaceboy.ridebuddy.ui.labelResource
import com.spaceboy.ridebuddy.ui.screens.MoreSettingsActions
import com.spaceboy.ridebuddy.ui.theme.Rs457Theme
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private var notificationAccessEnabled by mutableStateOf(false)
    private var appNotificationPermissionGranted by mutableStateOf(Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU)
    private var nearbyDeviceAccessGranted by mutableStateOf(false)
    private var preciseLocationGranted by mutableStateOf(false)
    private var legacyCallPermissionGranted by mutableStateOf(false)
    private var backgroundLocationGranted by mutableStateOf(false)
    private var lastAssociationConnectionAddress: String? = null
    private var navigationStartJob: Job? = null
    private val navigationStartStopGuard: NavigationStartStopGuard
        get() = appContainer.navigationStartStopGuard
    private val viewModel: MainViewModel by viewModels {
        MainViewModel.factory(appContainer)
    }

    private val bluetoothPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        refreshRuntimePermissionState()
        val essentialGranted = result
            .filterKeys { it != NotificationPermission }
            .values.all { it }
        if (essentialGranted) {
            startAssociation()
        } else {
            viewModel.showMessage("Nearby-device access is needed to connect to the motorcycle")
        }
    }

    private val onboardingNearbyPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        refreshRuntimePermissionState()
        viewModel.showMessage(if (nearbyDeviceAccessGranted) "Nearby-device access granted" else "Nearby-device access was not granted")
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

    private val appNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        appNotificationPermissionGranted = granted
        viewModel.showMessage(if (granted) "Riding alerts enabled" else "Phone-side riding alerts remain disabled")
    }

    private val associationApprovalLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        val manager = appContainer.bikeCompanionManager
        val bike = manager.acceptActivityResult(result.resultCode, result.data)
        if (bike != null) {
            connectNewAssociation(bike)
        } else if (result.resultCode == android.app.Activity.RESULT_CANCELED) {
            viewModel.showMessage("Pairing canceled")
        }
    }

    private val answerCallsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        legacyCallPermissionGranted = granted
        viewModel.setLegacyCallControls(granted)
        viewModel.showMessage(
            if (granted) "Legacy call compatibility enabled" else "Legacy call compatibility was not enabled",
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleShareIntent(intent)

        setContent {
            val uiState = viewModel.uiState.collectAsStateWithLifecycle().value
            val connectionState = viewModel.connectionState.collectAsStateWithLifecycle().value
            val telemetry = viewModel.telemetry.collectAsStateWithLifecycle().value
            val latestTelemetryReading = viewModel.latestTelemetryReading.collectAsStateWithLifecycle().value
            val identity = viewModel.identity.collectAsStateWithLifecycle().value
            val diagnostics = viewModel.diagnostics.collectAsStateWithLifecycle().value
            val bleCapture = viewModel.bleCapture.collectAsStateWithLifecycle().value
            val activeRide = viewModel.activeRide.collectAsStateWithLifecycle().value
            val liveRideSamples = viewModel.liveRideSamples.collectAsStateWithLifecycle().value
            val rides = viewModel.rides.collectAsStateWithLifecycle().value
            val insights = viewModel.insights.collectAsStateWithLifecycle().value
            val insightPeriod = viewModel.selectedInsightPeriod.collectAsStateWithLifecycle().value
            val guidance = viewModel.guidance.collectAsStateWithLifecycle().value
            val settings = viewModel.settings.collectAsStateWithLifecycle().value
            val autoStartSharedDestination = uiState.autoStartSharedDestination
            LaunchedEffect(autoStartSharedDestination?.requestId) {
                autoStartSharedDestination?.let { request ->
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
                    onCallerDisplayChanged = viewModel::setCallerDisplay,
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
                    onThemeModeChanged = viewModel::setThemeMode,
                    onDynamicColorChanged = viewModel::setDynamicColor,
                    onHighContrastChanged = viewModel::setHighContrast,
                    onBleCaptureEnabledChanged = viewModel::setBleCaptureEnabled,
                )
            }

            Rs457Theme(
                themeMode = settings.themeMode,
                dynamicColor = settings.dynamicColor,
                highContrast = settings.highContrast,
            ) {
                if (!settings.onboardingComplete) {
                    OnboardingScreen(
                        connectionState = connectionState,
                        bikeAssociated = bikeAssociation.bike != null,
                        associatedBikeLabel = bikeAssociation.bike?.let { "${it.name} • ${it.address.takeLast(5)}" },
                        nearbyDeviceAccessGranted = nearbyDeviceAccessGranted,
                        preciseLocationGranted = preciseLocationGranted,
                        notificationAccessEnabled = notificationAccessEnabled,
                        appNotificationPermissionGranted = appNotificationPermissionGranted,
                        legacyCallPermissionGranted = legacyCallPermissionGranted,
                        telemetryReceiving = telemetry != null,
                        authenticated = diagnostics.authenticated,
                        navigationConfigured = uiState.navigationKey.isConfigured,
                        onRequestNearbyDeviceAccess = ::requestOnboardingNearbyDeviceAccess,
                        onRequestPreciseLocation = { onboardingLocationPermissionLauncher.launch(LocationPermissions) },
                        onAssociateBike = ::requestBluetoothPermissionsAndScan,
                        onOpenNotificationAccess = ::openNotificationAccessSettings,
                        onRequestAppNotificationPermission = ::requestAppNotificationPermission,
                        onEnableLegacyCalls = { setLegacyCallControls(true) },
                        onSetUpNavigation = {
                            viewModel.completeOnboarding()
                            viewModel.openNavigationSettings()
                        },
                        onComplete = viewModel::completeOnboarding,
                    )
                } else MainScreen(
                    state = MainScreenState(
                        uiState = uiState,
                        connectionState = connectionState,
                        telemetry = telemetry,
                        latestTelemetryReceivedAtElapsedRealtime = latestTelemetryReading?.receivedAtElapsedRealtime,
                        identity = identity,
                        diagnostics = diagnostics,
                        bleCapture = bleCapture,
                        activeRide = activeRide,
                        liveRideSamples = liveRideSamples,
                        rides = rides,
                        insights = insights,
                        insightPeriod = insightPeriod,
                        guidance = guidance,
                        settings = settings,
                        bikeAssociation = bikeAssociation,
                        notificationAccessEnabled = notificationAccessEnabled,
                        callControlsEnabled = notificationAccessEnabled,
                        legacyCallPermissionGranted = legacyCallPermissionGranted,
                        backgroundLocationGranted = backgroundLocationGranted,
                    ),
                    actions = MainScreenActions(
                        onDestinationSelected = viewModel::selectDestination,
                        onOpenNavigationSettings = viewModel::openNavigationSettings,
                        onCloseNavigationSettings = viewModel::closeNavigationSettings,
                        onOpenDiagnostics = viewModel::openDiagnostics,
                        onCloseDiagnostics = viewModel::closeDiagnostics,
                        onSaveNavigationApiKey = viewModel::saveNavigationApiKey,
                        onRemoveNavigationApiKey = viewModel::removeNavigationApiKey,
                        onTestNavigationApiKey = viewModel::testNavigationApiKey,
                        onReconnect = ::reconnectToSavedBike,
                        onDisconnectBike = { BikeConnectionService.disconnect(this@MainActivity) },
                        onStartNavigation = ::startNavigation,
                        onOpenActiveNavigation = ::openActiveNavigation,
                        onStopNavigation = ::stopNavigation,
                        onSharedDestinationHandled = viewModel::clearSharedDestination,
                        onInsightPeriodSelected = viewModel::selectInsightPeriod,
                        onClearRideHistory = viewModel::clearRideHistory,
                        onExportRideHistory = ::exportRideHistory,
                        onOpenNotificationAccess = ::openNotificationAccessSettings,
                        onEnableCallControls = {
                            if (!notificationAccessEnabled) {
                                openNotificationAccessSettings()
                            } else {
                                viewModel.showMessage("Standard call controls are enabled")
                            }
                        },
                        onAssociateBike = ::requestBluetoothPermissionsAndScan,
                        onForgetBike = ::forgetBike,
                        onRideSelected = { ride ->
                            startActivity(RideDetailActivity.intent(this@MainActivity, ride.id))
                        },
                        onDistanceUnitsChanged = viewModel::setDistanceUnits,
                        onVoiceGuidanceChanged = viewModel::setVoiceGuidance,
                        onAvoidTollsChanged = viewModel::setAvoidTolls,
                        onAvoidHighwaysChanged = viewModel::setAvoidHighways,
                        onAvoidFerriesChanged = viewModel::setAvoidFerries,
                        onAutoStartSharedChanged = viewModel::setAutoStartSharedDestinations,
                        onMessageAlertsChanged = viewModel::setMessageAlerts,
                        onSocialAlertsChanged = viewModel::setSocialAlerts,
                        onEmailAlertsChanged = viewModel::setEmailAlerts,
                        settingsActions = settingsActions,
                        onResetOnboarding = viewModel::resetOnboarding,
                        onExportDiagnostics = ::exportDiagnostics,
                        onExportBleCapture = ::exportBleCapture,
                        onClearBleCapture = viewModel::clearBleCapture,
                        onRunStationaryTest = ::runStationaryTftTest,
                        onLegacyCallControlsChanged = ::setLegacyCallControls,
                        onOpenBackgroundLocationSettings = ::openAppPermissionSettings,
                        onOpenAppPermissions = ::openAppPermissionSettings,
                        onMessageShown = viewModel::clearTransientMessage,
                    ),
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        notificationAccessEnabled = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
        refreshRuntimePermissionState()
        backgroundLocationGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val container = appContainer
        container.bikeCompanionManager.refresh()
        container.bikeCompanionManager.ensurePresenceObservation()
        if (viewModel.connectionState.value !is BikeConnectionState.Disconnected &&
            viewModel.connectionState.value !is BikeConnectionState.Failed
        ) {
            BikeConnectionService.enableLocation(this)
        }
    }

    private fun refreshRuntimePermissionState() {
        nearbyDeviceAccessGranted = requiredNearbyDevicePermissions().all { permission ->
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }
        preciseLocationGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        legacyCallPermissionGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ANSWER_PHONE_CALLS) ==
            PackageManager.PERMISSION_GRANTED
        appNotificationPermissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, NotificationPermission) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestOnboardingNearbyDeviceAccess() {
        onboardingNearbyPermissionLauncher.launch(requiredNearbyDevicePermissions())
    }

    private fun requestAppNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appNotificationPermissionLauncher.launch(NotificationPermission)
        } else {
            appNotificationPermissionGranted = true
        }
    }

    private fun requestBluetoothPermissionsAndScan() {
        val requiredPermissions = requiredNearbyDevicePermissions()
        val missingPermissions = requiredPermissions.filter { permission ->
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isEmpty()) {
            startAssociation()
        } else {
            bluetoothPermissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    /**
     * Wired to the InfoScreen `Reconnect` button. If a bike is already associated, this
     * restarts the BikeConnectionService via [BikeConnectionService.restartConnect] so the
     * foreground promotion happens before GATT begins. When no bike is associated yet,
     * delegates to the CDM picker so the user can pick one.
     */
    private fun reconnectToSavedBike() {
        val bike = appContainer.bikeCompanionManager.state.value.bike
        if (bike == null) {
            requestBluetoothPermissionsAndScan()
            return
        }
        if (!BikeConnectionService.restartConnect(this, bike, launchedFromVisibleActivity = true)) {
            viewModel.showMessage("Unable to start connection service")
        }
    }

    private fun startAssociation() {
        val manager = appContainer.bikeCompanionManager
        val existing = manager.state.value.bike
        if (existing != null) {
            if (!BikeConnectionService.reconnect(this, existing, launchedFromVisibleActivity = true)) {
                viewModel.showMessage("Unable to start connection service")
            }
            return
        }
        if (!manager.state.value.supported) {
            viewModel.showMessage("Your phone does not support the Companion device setup feature required by RideBuddy")
            return
        }
        lastAssociationConnectionAddress = null
        viewModel.showMessage("Choose your motorcycle in the system device picker")
        manager.associate(
            launchApproval = { intentSender ->
                associationApprovalLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
            },
            onAssociated = ::connectNewAssociation,
            onFailure = viewModel::showMessage,
        )
    }

    private fun connectNewAssociation(bike: AssociatedBike) {
        if (lastAssociationConnectionAddress.equals(bike.address, ignoreCase = true)) return
        lastAssociationConnectionAddress = bike.address
        if (!BikeConnectionService.reconnect(this, bike, launchedFromVisibleActivity = true)) {
            viewModel.showMessage("Unable to start connection service")
        } else {
            viewModel.showMessage("Bike associated; connecting")
        }
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

    private fun setLegacyCallControls(enabled: Boolean) {
        if (!enabled) {
            viewModel.setLegacyCallControls(false)
            return
        }
        if (legacyCallPermissionGranted) {
            viewModel.setLegacyCallControls(true)
        } else {
            answerCallsPermissionLauncher.launch(Manifest.permission.ANSWER_PHONE_CALLS)
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
        if (viewModel.settings.value.autoStartSharedDestinations) {
            viewModel.queueAutoStartSharedDestination(destination)
        } else {
            viewModel.acceptSharedDestination(destination)
        }
    }

    private fun requiredNearbyDevicePermissions(): Array<String> =
        arrayOf(Manifest.permission.BLUETOOTH_CONNECT)

    private fun startNavigation(rawDestination: String) {
        val destination = rawDestination.trim()
        if (destination.isEmpty() || destination.length > MaxDestinationInputLength) {
            viewModel.showMessage(getString(R.string.destination_too_long))
            return
        }
        navigationStartJob?.cancel()
        viewModel.uiState.value.autoStartSharedDestination
            ?.requestId
            ?.let(viewModel::completeAutoStartSharedDestination)
        lifecycleScope.launch { startNavigation(destination, autoStartRequestId = null) }
    }

    private suspend fun startNavigation(rawDestination: String, autoStartRequestId: Long?) {
        val currentJob = currentCoroutineContext().job
        navigationStartJob?.takeIf { it !== currentJob }?.cancel()
        navigationStartJob = currentJob
        val container = appContainer
        var navigationStartAttemptId: Long? = null
        try {
            val destinationInput = rawDestination.trim()
            if (destinationInput.isEmpty() || destinationInput.length > MaxDestinationInputLength) {
                autoStartRequestId?.let(viewModel::completeAutoStartSharedDestination)
                viewModel.showMessage(getString(R.string.destination_too_long))
                return
            }
            if (autoStartRequestId != null &&
                viewModel.uiState.value.autoStartSharedDestination?.requestId != autoStartRequestId
            ) {
                return
            }
            navigationStartAttemptId = viewModel.beginNavigationStart()
            if (!viewModel.uiState.value.navigationKey.isConfigured) {
                autoStartRequestId?.let { requestId ->
                    viewModel.restoreAutoStartSharedDestination(requestId)
                }
                viewModel.openNavigationSettings()
                viewModel.showMessage("Add a Google Navigation API key first")
                return
            }
            val result = container.destinationParser.parse(destinationInput)
            if (autoStartRequestId != null &&
                viewModel.uiState.value.autoStartSharedDestination?.requestId != autoStartRequestId
            ) {
                return
            }
            result.fold(
                onSuccess = { destination ->
                    try {
                        startActivity(
                            NavigationActivity.intent(
                                this@MainActivity,
                                destination.latitude,
                                destination.longitude,
                                destination.title,
                            ),
                        )
                        navigationStartStopGuard.beginStart()
                        autoStartRequestId?.let(viewModel::completeAutoStartSharedDestination)
                        viewModel.clearSharedDestination()
                    } catch (error: Exception) {
                        val message = error.message ?: "Could not start navigation"
                        autoStartRequestId?.let { requestId ->
                            viewModel.restoreAutoStartSharedDestination(requestId, message)
                        }
                        viewModel.showMessage(message)
                    }
                },
                onFailure = { error ->
                    val message = error.message ?: "Could not read that destination"
                    autoStartRequestId?.let { requestId ->
                        viewModel.restoreAutoStartSharedDestination(requestId, message)
                    }
                    viewModel.showMessage(message)
                },
            )
        } finally {
            navigationStartAttemptId?.let(viewModel::finishNavigationStart)
            if (navigationStartJob === currentJob) {
                navigationStartJob = null
            }
        }
    }

    private fun openActiveNavigation() {
        runCatching {
            startActivity(NavigationActivity.activeGuidanceIntent(this))
        }.onFailure {
            viewModel.showMessage(getString(R.string.navigation_map_unavailable))
        }
    }

    private fun stopNavigation() {
        val stopRequestId = navigationStartStopGuard.beginStop() ?: return
        navigationStartJob?.cancel()
        runCatching {
            NavigationApi.getNavigator(this, object : NavigationApi.NavigatorListener {
                override fun onNavigatorReady(navigator: Navigator) {
                    if (!navigationStartStopGuard.isCurrentStop(stopRequestId)) return
                    val stopResult = runCatching(navigator::stopGuidance)
                    if (stopResult.isFailure) {
                        navigationStartStopGuard.finishStop(stopRequestId)
                        viewModel.showMessage(getString(R.string.navigation_end_request_failed))
                        return
                    }
                    appContainer.navigationGuidanceLifecycle
                        .release(navigator)
                    val cleanupFailure = listOf(
                        runCatching(navigator::unregisterServiceForNavUpdates),
                        runCatching(navigator::cleanup),
                    ).firstOrNull { it.isFailure }?.exceptionOrNull()
                    clearNavigationOutput()
                    navigationStartStopGuard.finishStop(stopRequestId)
                    if (cleanupFailure != null) {
                        viewModel.showMessage(getString(R.string.navigation_end_cleanup_failed))
                    }
                }

                override fun onError(errorCode: Int) {
                    if (!navigationStartStopGuard.isCurrentStop(stopRequestId)) return
                    navigationStartStopGuard.finishStop(stopRequestId)
                    viewModel.showMessage(getString(R.string.navigation_end_failed, errorCode))
                }
            })
        }.onFailure {
            if (navigationStartStopGuard.isCurrentStop(stopRequestId)) {
                navigationStartStopGuard.finishStop(stopRequestId)
                viewModel.showMessage(getString(R.string.navigation_end_request_failed))
            }
        }
    }

    private fun clearNavigationOutput() {
        appContainer.apply {
            navigationFeed.clear()
            runCatching(tftNavigationBridge::stop).onFailure {
                viewModel.showMessage(getString(R.string.navigation_tft_clear_failed))
            }
        }
    }

    private fun exportDiagnostics() {
        val value = viewModel.diagnostics.value
        val report = buildString {
            appendLine("RideBuddy diagnostics")
            appendLine("Connection: ${viewModel.connectionState.value}")
            appendLine("Companion link ready: ${value.authenticated}")
            appendLine("Protection phase: ${getString(value.protectionPhase.labelResource())}")
            appendLine(
                "Protection path: ${value.protectionPath?.let { getString(it.labelResource()) } ?: "unknown"}",
            )
            appendLine("Bonded: ${value.bonded ?: "unknown"}")
            appendLine("Active GATT operation: ${value.activeGattOperation ?: "none"}")
            appendLine("RSSI: ${value.rssi ?: "unknown"} dBm")
            appendLine("Telemetry rate: %.2f Hz".format(value.telemetryHz))
            appendLine("MTU: ${value.negotiatedMtu ?: "unknown"}")
            appendLine("Services: ${value.servicesDiscovered}")
            appendLine("Notifications: ${value.notificationsReceived}")
            appendLine("Descriptor writes: ${value.descriptorWritesCompleted}")
            appendLine("Characteristic reads: ${value.readsCompleted}")
            appendLine("Characteristic writes: ${value.writesCompleted}")
            appendLine("Malformed frames: ${value.malformedTelemetryFrames}")
            appendLine("Last error: ${value.lastError ?: "none"}")
            appendLine("\nGATT snapshot")
            value.serviceSnapshot.forEach(::appendLine)
            appendLine("\nRecent events")
            value.recentEvents.forEach(::appendLine)
            appendLine("\nRecent frames")
            value.recentFrames.forEach(::appendLine)
        }
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
        val rides = viewModel.rides.value
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val exportDir = File(cacheDir, "exports").also { directory ->
                        check(directory.exists() || directory.mkdirs()) { "Could not create the export directory" }
                    }
                    File(exportDir, "ride_history.csv").also { output ->
                        output.bufferedWriter().use { writer ->
                            writer.appendLine("started_at,ended_at,start_area,end_area,distance_km,duration_ms,average_speed_kph,maximum_speed_kph,average_rpm,maximum_rpm,average_consumption_l_per_100km,estimated_fuel_l,zero_to_60_ms,zero_to_100_ms")
                            rides.forEach { ride ->
                                fun String.escapeCsv() = "\"${replace("\"", "\"\"")}\""
                                writer.appendLine(
                                    listOf(
                                        ride.startedAtMillis,
                                        ride.endedAtMillis,
                                        (ride.startArea ?: "").escapeCsv(),
                                        (ride.endArea ?: "").escapeCsv(),
                                        ride.distanceKilometres,
                                        ride.durationMillis,
                                        ride.averageSpeedKph,
                                        ride.maximumSpeedKph,
                                        ride.averageRpm,
                                        ride.maximumRpm,
                                        ride.averageConsumptionLPer100Km,
                                        ride.estimatedFuelLitres,
                                        ride.zeroToSixtyMillis ?: "",
                                        ride.zeroToHundredMillis ?: "",
                                    ).joinToString(","),
                                )
                            }
                        }
                    }
                }
            }
            result.fold(
                onSuccess = { file ->
                    runCatching {
                        val uri = FileProvider.getUriForFile(this@MainActivity, "${packageName}.fileprovider", file)
                        startActivity(
                            Intent.createChooser(
                                Intent(Intent.ACTION_SEND)
                                    .setType("text/csv")
                                    .putExtra(Intent.EXTRA_SUBJECT, "RideBuddy ride history")
                                    .putExtra(Intent.EXTRA_STREAM, uri)
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                                "Export ride history",
                            ),
                        )
                    }.onFailure { viewModel.showMessage("Could not share ride history") }
                },
                onFailure = { viewModel.showMessage("Could not export ride history") },
            )
        }
    }

    private fun runStationaryTftTest() {
        val container = appContainer
        if (viewModel.connectionState.value !is BikeConnectionState.Connected || !viewModel.diagnostics.value.authenticated) {
            viewModel.showMessage("Connect and verify the companion link before testing")
            return
        }
        lifecycleScope.launch {
            when (val result = container.stationaryTftValidator.run()) {
                is StationaryTftTestResult.Failed -> {
                    viewModel.showMessage("TFT test stopped after ${result.completedWrites} acknowledged writes")
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
                }
                is StationaryTftTestResult.Succeeded -> {
                    val latestTelemetry = container.bikeConnection.latestTelemetryReading.value
                    if (latestTelemetry == null) {
                        viewModel.showMessage("TFT test result discarded because stationary telemetry was unavailable")
                        return@launch
                    }
                    if (latestTelemetry.frame.speedKilometresPerHour > 0.5) {
                        viewModel.showMessage("TFT test result discarded because the bike started moving")
                        return@launch
                    }
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Confirm TFT display")
                        .setMessage(
                            "The Bluetooth stack accepted ${result.acceptedWrites} test writes. " +
                                "While parked, did you see the maneuver, distance, test text, speed limit, and clear state on the TFT?",
                        )
                        .setPositiveButton("Looks correct") { _, _ ->
                            viewModel.showMessage("TFT display test completed")
                        }
                        .setNegativeButton("Did not appear") { _, _ ->
                            viewModel.showMessage("TFT display test needs investigation")
                        }
                        .show()
                }
            }
        }
    }

    private companion object {
        const val NotificationPermission = "android.permission.POST_NOTIFICATIONS"
        val LocationPermissions = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
    }
}

internal class NavigationStartStopGuard {
    private var generation = 0L
    private var stopInProgress = false

    @Synchronized
    fun beginStart(): Long {
        stopInProgress = false
        return ++generation
    }

    @Synchronized
    fun beginStop(): Long? {
        if (stopInProgress) return null
        stopInProgress = true
        return ++generation
    }

    @Synchronized
    fun isCurrentStop(requestId: Long): Boolean = stopInProgress && generation == requestId

    @Synchronized
    fun finishStop(requestId: Long) {
        if (generation == requestId) stopInProgress = false
    }
}
