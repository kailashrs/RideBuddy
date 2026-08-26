package com.spaceboy.ridebuddy.ui

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
import com.spaceboy.ridebuddy.domain.BikeConnectionState
import com.spaceboy.ridebuddy.domain.BikeIdentity
import com.spaceboy.ridebuddy.domain.BleDiagnostics
import com.spaceboy.ridebuddy.ui.screens.MoreSettingsActions

data class MainScreenState(
    val uiState: MainUiState,
    val connectionState: BikeConnectionState,
    val telemetry: TelemetryFrame?,
    val identity: BikeIdentity,
    val diagnostics: BleDiagnostics,
    val bleCapture: BleCaptureState,
    val activeRide: ActiveRide?,
    val liveRideSamples: List<RideSample>,
    val liveRideMetrics: LiveRideMetrics,
    val rides: List<Ride>,
    val insights: RideInsights,
    val insightPeriod: InsightPeriod,
    val guidance: GuidanceState,
    val settings: AppSettings,
    val bikeAssociation: BikeAssociationState,
    val notificationAccessEnabled: Boolean,
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
    val onReconnect: () -> Unit,
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
