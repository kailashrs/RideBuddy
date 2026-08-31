package com.spaceboy.ridebuddy.ui

import androidx.compose.runtime.Immutable
import com.spaceboy.ridebuddy.MainUiState
import com.spaceboy.ridebuddy.TopLevelDestination
import com.spaceboy.ridebuddy.ble.BleCaptureState
import com.spaceboy.ridebuddy.ble.TelemetryFrame
import com.spaceboy.ridebuddy.core.companion.BikeAssociationState
import com.spaceboy.ridebuddy.core.navigation.GuidanceState
import com.spaceboy.ridebuddy.data.ActiveRide
import com.spaceboy.ridebuddy.data.AppSettings
import com.spaceboy.ridebuddy.data.DistanceUnits
import com.spaceboy.ridebuddy.data.InsightPeriod
import com.spaceboy.ridebuddy.data.LiveRideMetrics
import com.spaceboy.ridebuddy.data.Ride
import com.spaceboy.ridebuddy.data.RideInsights
import com.spaceboy.ridebuddy.data.RideSample
import com.spaceboy.ridebuddy.data.RideWeekSummary
import com.spaceboy.ridebuddy.domain.BikeConnectionState
import com.spaceboy.ridebuddy.domain.BikeIdentity
import com.spaceboy.ridebuddy.domain.BleDiagnostics
import com.spaceboy.ridebuddy.ui.screens.MoreSettingsActions
import kotlinx.coroutines.flow.StateFlow

/**
 * The streams that update at the bike's telemetry frame rate.
 *
 * They are handed down as flows rather than values on purpose: reading them where the screens are
 * chosen would invalidate the whole content tree four times a second, so History, Insights and
 * Settings would re-compose at 4 Hz while the rider is not even looking at Live. Each screen
 * collects only the streams it actually draws, as late as it can.
 */
@Immutable
data class LiveTelemetryStreams(
    val telemetry: StateFlow<TelemetryFrame?>,
    val diagnostics: StateFlow<BleDiagnostics>,
    val activeRide: StateFlow<ActiveRide?>,
    val rideSamples: StateFlow<List<RideSample>>,
    val rideMetrics: StateFlow<LiveRideMetrics>,
)

/**
 * Everything the main screen draws, as one immutable value.
 *
 * Bundled rather than passed as loose parameters so the screen has a single, stable
 * signature, and `@Immutable` so Compose can skip recomposition when it has not changed.
 * Note that the frame-rate streams are deliberately *not* flattened into it — see
 * [LiveTelemetryStreams].
 */
@Immutable
data class MainScreenState(
    val uiState: MainUiState,
    val connectionState: BikeConnectionState,
    val identity: BikeIdentity,
    val bleCapture: BleCaptureState,
    val live: LiveTelemetryStreams,
    val rides: List<Ride>,
    val insights: RideInsights,
    val insightPeriod: InsightPeriod,
    val weekSummary: RideWeekSummary,
    val guidance: GuidanceState,
    val settings: AppSettings,
    val bikeAssociation: BikeAssociationState,
    val notificationAccessEnabled: Boolean,
    val legacyCallPermissionGranted: Boolean,
    val backgroundLocationGranted: Boolean,
)

/**
 * Every callback the main screen can raise.
 *
 * Grouping them keeps the screen decoupled from the Activity that implements them, which is
 * what lets the whole tree be previewed and tested without one.
 */
@Immutable
data class MainScreenActions(
    val onDestinationSelected: (TopLevelDestination) -> Unit,
    val onOpenNavigationSettings: () -> Unit,
    val onCloseNavigationSettings: () -> Unit,
    val onOpenDiagnostics: () -> Unit,
    val onCloseDiagnostics: () -> Unit,
    val onSaveNavigationApiKey: (String) -> Unit,
    val onRemoveNavigationApiKey: () -> Unit,
    val onTestNavigationApiKey: () -> Unit,
    val onDisconnectBike: () -> Unit,
    val onStartNavigation: (String) -> Unit,
    val onOpenActiveNavigation: () -> Unit,
    val onStopNavigation: () -> Unit,
    val onSharedDestinationHandled: () -> Unit,
    val onCancelNavigationStart: () -> Unit,
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
