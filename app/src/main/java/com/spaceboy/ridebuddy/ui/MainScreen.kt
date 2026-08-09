package com.spaceboy.ridebuddy.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

import com.spaceboy.ridebuddy.MainUiState
import com.spaceboy.ridebuddy.TopLevelDestination
import com.spaceboy.ridebuddy.ble.BikeScanState
import com.spaceboy.ridebuddy.ble.BleCaptureState
import com.spaceboy.ridebuddy.ble.DiscoveredBike
import com.spaceboy.ridebuddy.ble.TelemetryFrame
import com.spaceboy.ridebuddy.core.navigation.GuidanceState
import com.spaceboy.ridebuddy.core.companion.BikeAssociationState
import com.spaceboy.ridebuddy.data.ActiveRide
import com.spaceboy.ridebuddy.data.InsightPeriod
import com.spaceboy.ridebuddy.data.Ride
import com.spaceboy.ridebuddy.data.RideInsights
import com.spaceboy.ridebuddy.data.RideSample
import com.spaceboy.ridebuddy.data.AppSettings
import com.spaceboy.ridebuddy.data.DistanceUnits
import com.spaceboy.ridebuddy.domain.BikeConnectionState
import com.spaceboy.ridebuddy.domain.BikeIdentity
import com.spaceboy.ridebuddy.domain.BleDiagnostics
import com.spaceboy.ridebuddy.ui.screens.HistoryScreen
import com.spaceboy.ridebuddy.ui.screens.InfoScreen
import com.spaceboy.ridebuddy.ui.screens.InsightsScreen
import com.spaceboy.ridebuddy.ui.screens.LiveScreen
import com.spaceboy.ridebuddy.ui.screens.SettingsScreen
import com.spaceboy.ridebuddy.ui.screens.MoreSettingsActions
import com.spaceboy.ridebuddy.ui.screens.NavigationSettingsScreen
import com.spaceboy.ridebuddy.ui.screens.DiagnosticsScreen

private data class DestinationItem(
    val destination: TopLevelDestination,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
)

private val destinations = listOf(
    DestinationItem(TopLevelDestination.Live, "Live", Icons.Filled.Speed, Icons.Outlined.Speed),
    DestinationItem(TopLevelDestination.History, "History", Icons.Filled.History, Icons.Outlined.History),
    DestinationItem(TopLevelDestination.Insights, "Insights", Icons.Filled.Insights, Icons.Outlined.Insights),
    DestinationItem(TopLevelDestination.Info, "Info", Icons.Filled.Info, Icons.Outlined.Info),
    DestinationItem(TopLevelDestination.More, "Settings", Icons.Filled.Settings, Icons.Outlined.Settings),
)

data class MainScreenState(
    val uiState: MainUiState,
    val scanState: BikeScanState,
    val discoveredBikes: List<DiscoveredBike>,
    val connectionState: BikeConnectionState,
    val telemetry: TelemetryFrame?,
    val latestTelemetryReceivedAtElapsedRealtime: Long?,
    val identity: BikeIdentity,
    val diagnostics: BleDiagnostics,
    val bleCapture: BleCaptureState,
    val activeRide: ActiveRide?,
    val liveRideSamples: List<RideSample>,
    val rides: List<Ride>,
    val insights: RideInsights,
    val insightPeriod: InsightPeriod,
    val guidance: GuidanceState,
    val settings: AppSettings,
    val bikeAssociation: BikeAssociationState,
    val notificationAccessEnabled: Boolean,
    val callControlsEnabled: Boolean,
    val legacyCallPermissionGranted: Boolean,
    val backgroundLocationGranted: Boolean,
)

data class MainScreenActions(
    val onDestinationSelected: (TopLevelDestination) -> Unit,
    val onOpenNavigationSettings: () -> Unit,
    val onCloseNavigationSettings: () -> Unit,
    val onOpenDiagnostics: () -> Unit,
    val onCloseDiagnostics: () -> Unit,
    val onSaveNavigationApiKey: (String) -> Unit,
    val onRemoveNavigationApiKey: () -> Unit,
    val onTestNavigationApiKey: () -> Unit,
    val onFindBike: () -> Unit,
    val onConnectBike: (DiscoveredBike) -> Unit,
    val onDisconnectBike: () -> Unit,
    val onStartNavigation: (String) -> Unit,
    val onOpenActiveNavigation: () -> Unit,
    val onStopNavigation: () -> Unit,
    val onSharedDestinationHandled: () -> Unit,
    val onInsightPeriodSelected: (InsightPeriod) -> Unit,
    val onClearRideHistory: () -> Unit,
    val onExportRideHistory: () -> Unit,
    val onOpenNotificationAccess: () -> Unit,
    val onEnableCallControls: () -> Unit,
    val onAssociateBike: () -> Unit,
    val onForgetBike: () -> Unit,
    val onRideSelected: (Ride) -> Unit,
    val onDistanceUnitsChanged: (DistanceUnits) -> Unit,
    val onVoiceGuidanceChanged: (Boolean) -> Unit,
    val onAvoidTollsChanged: (Boolean) -> Unit,
    val onAvoidHighwaysChanged: (Boolean) -> Unit,
    val onAvoidFerriesChanged: (Boolean) -> Unit,
    val onAutoStartSharedChanged: (Boolean) -> Unit,
    val onMessageAlertsChanged: (Boolean) -> Unit,
    val onSocialAlertsChanged: (Boolean) -> Unit,
    val onEmailAlertsChanged: (Boolean) -> Unit,
    val settingsActions: MoreSettingsActions,
    val onResetOnboarding: () -> Unit,
    val onExportDiagnostics: () -> Unit,
    val onExportBleCapture: () -> Unit,
    val onClearBleCapture: () -> Unit,
    val onRunStationaryTest: () -> Unit,
    val onLegacyCallControlsChanged: (Boolean) -> Unit,
    val onOpenBackgroundLocationSettings: () -> Unit,
    val onOpenAppPermissions: () -> Unit,
    val onMessageShown: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    state: MainScreenState,
    actions: MainScreenActions,
) = with(state) {
    with(actions) {
        val snackbarHostState = remember { SnackbarHostState() }
        val destinationStateHolder = rememberSaveableStateHolder()
        val topBarScrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
        LaunchedEffect(uiState.transientMessage) {
            uiState.transientMessage?.let { message ->
                snackbarHostState.showSnackbar(message)
                onMessageShown()
            }
        }

        BackHandler(enabled = uiState.isDiagnosticsOpen) {
            onCloseDiagnostics()
        }
        BackHandler(enabled = uiState.isNavigationSettingsOpen && !uiState.isDiagnosticsOpen) {
            onCloseNavigationSettings()
        }
        BackHandler(enabled = !uiState.isDiagnosticsOpen && !uiState.isNavigationSettingsOpen && uiState.selectedDestination != TopLevelDestination.Live) {
            onDestinationSelected(TopLevelDestination.Live)
        }

        val title = when {
            uiState.isNavigationSettingsOpen -> "Navigation"
            uiState.isDiagnosticsOpen -> "Diagnostics"
            else -> destinations.first { it.destination == uiState.selectedDestination }.label
        }
        val topBar: @Composable () -> Unit = {
            val topBarColors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
                scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            )
            if (!uiState.isNavigationSettingsOpen && !uiState.isDiagnosticsOpen && uiState.selectedDestination != TopLevelDestination.Live) {
                LargeTopAppBar(
                    title = { Text(title) },
                    scrollBehavior = topBarScrollBehavior,
                    colors = topBarColors,
                )
            } else {
                TopAppBar(
                    title = { Text(title) },
                    colors = topBarColors,
                    scrollBehavior = topBarScrollBehavior,
                    navigationIcon = {
                        if (uiState.isNavigationSettingsOpen || uiState.isDiagnosticsOpen) {
                            IconButton(onClick = if (uiState.isDiagnosticsOpen) onCloseDiagnostics else onCloseNavigationSettings) {
                                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                            }
                        }
                    },
                )
            }
        }

        val screenContent: @Composable (PaddingValues) -> Unit = { padding ->
            destinationStateHolder.SaveableStateProvider(uiState.saveableContentKey()) {
                ScreenContent(
                    modifier = Modifier.padding(padding),
                    state = state,
                    actions = actions,
                )
            }
        }

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            if (maxWidth >= 600.dp) {
                Row(modifier = Modifier.fillMaxSize()) {
                    NavigationRail(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                        destinations.forEach { item ->
                            val selected = item.destination == uiState.selectedDestination
                            NavigationRailItem(
                                modifier = Modifier.testTag("top-level-${item.destination.name}"),
                                selected = selected,
                                onClick = { onDestinationSelected(item.destination) },
                                icon = { Icon(if (selected) item.selectedIcon else item.unselectedIcon, contentDescription = null) },
                                label = { Text(item.label) },
                            )
                        }
                    }
                    Scaffold(
                        modifier = Modifier.weight(1f).nestedScroll(topBarScrollBehavior.nestedScrollConnection),
                        topBar = topBar,
                        snackbarHost = { SnackbarHost(snackbarHostState) },
                        content = screenContent,
                    )
                }
            } else {
                Scaffold(
                    modifier = Modifier.fillMaxSize().nestedScroll(topBarScrollBehavior.nestedScrollConnection),
                    topBar = topBar,
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    bottomBar = {
                        NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                            destinations.forEach { item ->
                                val selected = item.destination == uiState.selectedDestination
                                NavigationBarItem(
                                    modifier = Modifier.testTag("top-level-${item.destination.name}"),
                                    selected = selected,
                                    onClick = { onDestinationSelected(item.destination) },
                                    icon = { Icon(if (selected) item.selectedIcon else item.unselectedIcon, contentDescription = null) },
                                    label = { Text(item.label) },
                                )
                            }
                        }
                    },
                    content = screenContent,
                )
            }
        }
    }
}

private fun MainUiState.saveableContentKey(): String = when {
    isNavigationSettingsOpen -> "navigation_settings"
    isDiagnosticsOpen -> "diagnostics"
    else -> selectedDestination.name
}

@Composable
private fun ScreenContent(
    modifier: Modifier,
    state: MainScreenState,
    actions: MainScreenActions,
) = with(state) {
    with(actions) actionScope@{
        if (uiState.isNavigationSettingsOpen) {
            NavigationSettingsScreen(
                modifier = modifier,
                state = uiState.navigationKey,
                onSave = onSaveNavigationApiKey,
                onRemove = onRemoveNavigationApiKey,
                onTest = onTestNavigationApiKey,
                settings = settings,
                onVoiceGuidanceChanged = onVoiceGuidanceChanged,
                onAvoidTollsChanged = onAvoidTollsChanged,
                onAvoidHighwaysChanged = onAvoidHighwaysChanged,
                onAvoidFerriesChanged = onAvoidFerriesChanged,
            )
            return@actionScope
        }
        if (uiState.isDiagnosticsOpen) {
            DiagnosticsScreen(
                modifier = modifier,
                diagnostics = diagnostics,
                bleCapture = bleCapture,
                deviceAddress = bikeAssociation.bike?.address,
                notificationAccessEnabled = notificationAccessEnabled,
                onExport = onExportDiagnostics,
            )
            return@actionScope
        }

        when (uiState.selectedDestination) {
            TopLevelDestination.Live -> LiveScreen(
                modifier = modifier,
                scanState = scanState,
                discoveredBikes = discoveredBikes,
                sharedDestination = uiState.sharedDestination,
                sharedDestinationError = uiState.sharedDestinationError,
                isNavigationStarting = uiState.isNavigationStarting,
                connectionState = connectionState,
                telemetry = telemetry,
                activeRide = activeRide,
                liveSamples = liveRideSamples,
                diagnostics = diagnostics,
                lastRide = rides.firstOrNull(),
                guidance = guidance,
                units = settings.distanceUnits,
                onFindBike = onFindBike,
                onConnectBike = onConnectBike,
                onDisconnectBike = onDisconnectBike,
                onStartNavigation = onStartNavigation,
                onOpenActiveNavigation = onOpenActiveNavigation,
                onStopNavigation = onStopNavigation,
                onSharedDestinationHandled = onSharedDestinationHandled,
            )
            TopLevelDestination.History -> HistoryScreen(modifier, rides, settings.distanceUnits, onRideSelected)
            TopLevelDestination.Insights -> InsightsScreen(
                modifier = modifier,
                insights = insights,
                rides = rides,
                units = settings.distanceUnits,
                selectedPeriod = insightPeriod,
                onPeriodSelected = onInsightPeriodSelected,
            )
            TopLevelDestination.Info -> InfoScreen(
                modifier = modifier,
                navigationConfigured = uiState.navigationKey.isConfigured,
                connectionState = connectionState,
                latestTelemetryReceivedAtElapsedRealtime = latestTelemetryReceivedAtElapsedRealtime,
                identity = identity,
                diagnostics = diagnostics,
                deviceAddress = bikeAssociation.bike?.address,
                notificationAccessEnabled = notificationAccessEnabled,
                onReconnect = onFindBike,
            )
            TopLevelDestination.More -> SettingsScreen(
                modifier = modifier,
                navigationKey = uiState.navigationKey,
                onOpenNavigationSettings = onOpenNavigationSettings,
                diagnostics = diagnostics,
                bleCapture = bleCapture,
                rideCount = rides.size,
                onClearRideHistory = onClearRideHistory,
                onExportRideHistory = onExportRideHistory,
                onOpenNotificationAccess = onOpenNotificationAccess,
                onEnableCallControls = onEnableCallControls,
                bikeAssociation = bikeAssociation,
                onAssociateBike = onAssociateBike,
                onForgetBike = onForgetBike,
                settings = settings,
                onDistanceUnitsChanged = onDistanceUnitsChanged,
                onAutoStartSharedChanged = onAutoStartSharedChanged,
                onMessageAlertsChanged = onMessageAlertsChanged,
                onSocialAlertsChanged = onSocialAlertsChanged,
                onEmailAlertsChanged = onEmailAlertsChanged,
                settingsActions = settingsActions,
                onResetOnboarding = onResetOnboarding,
                onExportDiagnostics = onExportDiagnostics,
                onExportBleCapture = onExportBleCapture,
                onClearBleCapture = onClearBleCapture,
                onOpenDiagnostics = onOpenDiagnostics,
                onRunStationaryTest = onRunStationaryTest,
                notificationAccessEnabled = notificationAccessEnabled,
                callControlsEnabled = callControlsEnabled,
                legacyCallPermissionGranted = legacyCallPermissionGranted,
                onLegacyCallControlsChanged = onLegacyCallControlsChanged,
                backgroundLocationGranted = backgroundLocationGranted,
                onOpenBackgroundLocationSettings = onOpenBackgroundLocationSettings,
                onOpenAppPermissions = onOpenAppPermissions,
            )
        }
    }
}
