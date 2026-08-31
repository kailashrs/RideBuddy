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
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

import com.spaceboy.ridebuddy.MainUiState
import com.spaceboy.ridebuddy.TopLevelDestination
import com.spaceboy.ridebuddy.ui.screens.HistoryScreen
import com.spaceboy.ridebuddy.ui.screens.InfoScreen
import com.spaceboy.ridebuddy.ui.screens.InsightsScreen
import com.spaceboy.ridebuddy.ui.screens.LiveScreen
import com.spaceboy.ridebuddy.ui.screens.SettingsScreen
import com.spaceboy.ridebuddy.ui.screens.NavigationSettingsScreen
import com.spaceboy.ridebuddy.ui.screens.DiagnosticsScreen

/** One entry in the bottom navigation bar. Icons are paired filled/outlined per Material. */
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

/**
 * The app's chrome: top bar, navigation bar, snackbar host, and back handling.
 *
 * Content is a slot rather than a parameter so this composable never touches screen state,
 * and so the shell can be previewed and tested on its own.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    uiState: MainUiState,
    actions: MainScreenActions,
    content: @Composable (Modifier) -> Unit,
) {
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
                content(Modifier.padding(padding))
            }
        }

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            // Force the Scaffold (which owns the pinned LargeTopAppBar) to be rebuilt from scratch
            // whenever the navigation group identity changes. The state holder already keys
            // screen-level state on the same identifier, so SaveableStateProvider continues
            // to preserve each destination's scroll/field state across navigation.
            val layoutKey = "${maxWidth >= 600.dp}|${uiState.saveableContentKey()}"
            if (maxWidth >= 600.dp) {
                Row(modifier = Modifier.fillMaxSize()) {
                    NavigationRail(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                        destinations.forEach { item ->
                            val selected = item.destination == uiState.selectedDestination
                            NavigationRailItem(
                                modifier = Modifier.testTag("top-level-${item.destination.name}"),
                                selected = selected,
                                onClick = { onDestinationSelected(item.destination) },
                                icon = {
                                    Icon(
                                        if (selected) item.selectedIcon else item.unselectedIcon,
                                        contentDescription = null
                                    )
                                },
                                label = { Text(item.label) },
                            )
                        }
                    }
                    key(layoutKey) {
                        Scaffold(
                            modifier = Modifier.weight(1f).nestedScroll(topBarScrollBehavior.nestedScrollConnection),
                            topBar = topBar,
                            snackbarHost = { SnackbarHost(snackbarHostState) },
                            content = screenContent,
                        )
                    }
                }
            } else {
                key(layoutKey) {
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
                                        icon = {
                                            Icon(
                                                if (selected) item.selectedIcon else item.unselectedIcon,
                                                contentDescription = null
                                            )
                                        },
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
}

/**
 * Identifies which screen is showing, for the saveable state holder.
 *
 * The two overlays get their own keys rather than sharing the underlying destination's, so
 * opening and closing settings or diagnostics does not discard the scroll position of the
 * screen behind it.
 */
private fun MainUiState.saveableContentKey(): String = when {
    isNavigationSettingsOpen -> "navigation_settings"
    isDiagnosticsOpen -> "diagnostics"
    else -> selectedDestination.name
}

/**
 * Chooses and renders the current screen.
 *
 * The two overlays take precedence over the selected destination, which is what makes
 * closing them a state change rather than a navigation step.
 */
@Composable
internal fun MainScreenContent(
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
                live = live,
                bleCapture = bleCapture,
                connectionState = connectionState,
                identity = identity,
                deviceAddress = bikeAssociation.bike?.address,
                notificationAccessEnabled = notificationAccessEnabled,
                onExport = onExportDiagnostics,
            )
            return@actionScope
        }

        when (uiState.selectedDestination) {
            TopLevelDestination.Live -> LiveScreen(
                modifier = modifier,
                sharedDestination = uiState.sharedDestination,
                sharedDestinationError = uiState.sharedDestinationError,
                isNavigationStarting = uiState.isNavigationStarting,
                connectionState = connectionState,
                live = live,
                lastRide = rides.firstOrNull(),
                guidance = guidance,
                units = settings.distanceUnits,
                onConnectBike = onAssociateBike,
                onDisconnectBike = onDisconnectBike,
                onStartNavigation = onStartNavigation,
                onOpenActiveNavigation = onOpenActiveNavigation,
                onStopNavigation = onStopNavigation,
                onSharedDestinationHandled = onSharedDestinationHandled,
                onCancelNavigationStart = onCancelNavigationStart,
            )

            TopLevelDestination.History ->
                HistoryScreen(modifier, rides, weekSummary, settings.distanceUnits, onRideSelected)
            TopLevelDestination.Insights -> InsightsScreen(
                modifier = modifier,
                insights = insights,
                units = settings.distanceUnits,
                selectedPeriod = insightPeriod,
                onPeriodSelected = onInsightPeriodSelected,
            )

            TopLevelDestination.Info -> InfoScreen(
                modifier = modifier,
                navigationConfigured = uiState.navigationKey.isConfigured,
                connectionState = connectionState,
                identity = identity,
                notificationAccessEnabled = notificationAccessEnabled,
            )

            TopLevelDestination.More -> SettingsScreen(
                modifier = modifier,
                navigationKey = uiState.navigationKey,
                onOpenNavigationSettings = onOpenNavigationSettings,
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
                legacyCallPermissionGranted = legacyCallPermissionGranted,
                onLegacyCallControlsChanged = onLegacyCallControlsChanged,
                backgroundLocationGranted = backgroundLocationGranted,
                onOpenBackgroundLocationSettings = onOpenBackgroundLocationSettings,
                onOpenAppPermissions = onOpenAppPermissions,
            )
        }
    }
}
