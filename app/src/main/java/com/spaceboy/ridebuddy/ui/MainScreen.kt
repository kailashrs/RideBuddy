package com.spaceboy.ridebuddy.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarResult
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
import androidx.compose.ui.text.style.TextOverflow

import com.spaceboy.ridebuddy.MainUiState
import com.spaceboy.ridebuddy.TopLevelDestination
import com.spaceboy.ridebuddy.ui.screens.HistoryScreen
import com.spaceboy.ridebuddy.ui.screens.InsightsScreen
import com.spaceboy.ridebuddy.ui.screens.LiveScreen
import com.spaceboy.ridebuddy.ui.screens.SettingsScreen
import com.spaceboy.ridebuddy.ui.screens.NavigationSettingsScreen
import com.spaceboy.ridebuddy.ui.screens.DiagnosticsScreen

/** One entry in the navigation bar. Icons are paired filled/outlined per Material. */
private data class DestinationItem(
    val destination: TopLevelDestination,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
)

private val destinations = listOf(
    DestinationItem(TopLevelDestination.Live, "Live", Icons.Filled.Speed, Icons.Outlined.Speed),
    DestinationItem(TopLevelDestination.Insights, "Insights", Icons.Filled.Insights, Icons.Outlined.Insights),
    DestinationItem(TopLevelDestination.History, "History", Icons.Filled.History, Icons.Outlined.History),
    DestinationItem(TopLevelDestination.Settings, "Settings", Icons.Filled.Settings, Icons.Outlined.Settings),
)

/**
 * The app's chrome: top app bar, navigation bar or rail, snackbar host, and back handling.
 *
 * Navigation settings and diagnostics are child screens of Settings: they get an Up arrow and
 * the navigation bar steps aside for them, as Material asks of any screen below the top level.
 * Content is a slot so this composable never touches screen state.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    uiState: MainUiState,
    actions: MainScreenActions,
    content: @Composable (Modifier) -> Unit,
) = with(actions) {
    val snackbarHostState = remember { SnackbarHostState() }
    val destinationStateHolder = rememberSaveableStateHolder()
    LaunchedEffect(uiState.transientMessage) {
        uiState.transientMessage?.let { message ->
            // A failed share is offered again here; it has no other place to wait now.
            val retry = uiState.failedShare != null
            val result = snackbarHostState.showSnackbar(
                message,
                actionLabel = if (retry) "Retry" else null,
                duration = if (retry) SnackbarDuration.Long else SnackbarDuration.Short,
            )
            // Retry before the message is cleared: clearing it also forgets the failed share.
            if (result == SnackbarResult.ActionPerformed) onRetryShare()
            onMessageShown()
        }
    }

    val childScreen = uiState.isNavigationSettingsOpen || uiState.isDiagnosticsOpen
    val closeChild = if (uiState.isDiagnosticsOpen) onCloseDiagnostics else onCloseNavigationSettings
    BackHandler(enabled = childScreen, onBack = closeChild)
    BackHandler(enabled = !childScreen && uiState.selectedDestination != TopLevelDestination.Live) {
        onDestinationSelected(TopLevelDestination.Live)
    }
    val title = when {
        uiState.isNavigationSettingsOpen -> "Navigation"
        uiState.isDiagnosticsOpen -> "Connection details"
        else -> destinations.first { it.destination == uiState.selectedDestination }.label
    }
    val contentKey = uiState.saveableContentKey()

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val useRail = maxWidth >= 600.dp
        Row(Modifier.fillMaxSize()) {
            if (useRail && !childScreen) {
                NavigationRail {
                    destinations.forEach { item ->
                        val selected = item.destination == uiState.selectedDestination
                        NavigationRailItem(
                            selected = selected,
                            onClick = { onDestinationSelected(item.destination) },
                            icon = { Icon(if (selected) item.selectedIcon else item.unselectedIcon, contentDescription = null) },
                            label = { Text(item.label) },
                            modifier = Modifier.testTag("top-level-${item.destination.name}"),
                        )
                    }
                }
            }
            // Only the scroll state is keyed per screen, so each starts with an unscrolled top
            // app bar while the Scaffold and navigation bar persist and animate between tabs.
            val scrollBehavior = key(contentKey) { TopAppBarDefaults.pinnedScrollBehavior() }
            Scaffold(
                modifier = Modifier.weight(1f).nestedScroll(scrollBehavior.nestedScrollConnection),
                topBar = {
                    TopAppBar(
                        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        navigationIcon = {
                            if (childScreen) {
                                IconButton(onClick = closeChild) {
                                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                                }
                            }
                        },
                        scrollBehavior = scrollBehavior,
                    )
                },
                bottomBar = {
                    if (!useRail && !childScreen) {
                        NavigationBar {
                            destinations.forEach { item ->
                                val selected = item.destination == uiState.selectedDestination
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = { onDestinationSelected(item.destination) },
                                    icon = { Icon(if (selected) item.selectedIcon else item.unselectedIcon, contentDescription = null) },
                                    label = { Text(item.label) },
                                    modifier = Modifier.testTag("top-level-${item.destination.name}"),
                                )
                            }
                        }
                    }
                },
                snackbarHost = { SnackbarHost(snackbarHostState) },
            ) { padding ->
                destinationStateHolder.SaveableStateProvider(contentKey) {
                    content(Modifier.padding(padding))
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
    with(actions) {
        when {
            uiState.isNavigationSettingsOpen -> NavigationSettingsScreen(
                modifier = modifier,
                state = uiState.navigationKey,
                onSave = onSaveNavigationApiKey,
                onRemove = onRemoveNavigationApiKey,
                onTest = onTestNavigationApiKey,
            )
            uiState.isDiagnosticsOpen -> DiagnosticsScreen(state, actions, modifier)
            else -> when (uiState.selectedDestination) {
                TopLevelDestination.Live -> LiveScreen(
                    modifier = modifier,
                    isNavigationStarting = uiState.isNavigationStarting,
                    connectionState = connectionState,
                    bikeAssociated = bikeAssociation.bike != null,
                    pairingInProgress = bikeAssociation.associationInProgress,
                    live = live,
                    lastRide = rides.firstOrNull(),
                    guidance = guidance,
                    units = settings.distanceUnits,
                    onConnectBike = onAssociateBike,
                    onDisconnectBike = onDisconnectBike,
                    onEndRide = onEndRide,
                    destinations = destinations,
                    onNavigateTo = onNavigateTo,
                    onOpenGoogleMaps = onOpenGoogleMaps,
                    onRenameDestination = onRenameDestination,
                    onDeleteDestination = onDeleteDestination,
                    onOpenActiveNavigation = onOpenActiveNavigation,
                    onStopNavigation = onStopNavigation,
                    onCancelNavigationStart = onCancelNavigationStart,
                    onRideSelected = onRideSelected,
                )
                TopLevelDestination.History -> HistoryScreen(
                    modifier = modifier,
                    rides = rides,
                    units = settings.distanceUnits,
                    onRideSelected = onRideSelected,
                    trips = trips,
                    onTripSelected = onTripSelected,
                    onSaveTrip = onSaveTrip,
                )
                TopLevelDestination.Insights -> InsightsScreen(
                    modifier = modifier,
                    insights = insights,
                    units = settings.distanceUnits,
                    selectedPeriod = insightPeriod,
                    onPeriodSelected = onInsightPeriodSelected,
                )
                TopLevelDestination.Settings -> SettingsScreen(state, actions, modifier)
            }
        }
    }
}
