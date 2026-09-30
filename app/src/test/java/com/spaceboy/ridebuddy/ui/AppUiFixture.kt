package com.spaceboy.ridebuddy.ui

import com.spaceboy.ridebuddy.MainUiState
import com.spaceboy.ridebuddy.NavigationKeyUiState
import com.spaceboy.ridebuddy.TopLevelDestination
import com.spaceboy.ridebuddy.ble.BleCaptureState
import com.spaceboy.ridebuddy.ble.BluetoothAddress
import com.spaceboy.ridebuddy.core.companion.AssociatedBike
import com.spaceboy.ridebuddy.core.companion.BikeAssociationState
import com.spaceboy.ridebuddy.core.navigation.GuidanceState
import com.spaceboy.ridebuddy.data.*
import com.spaceboy.ridebuddy.domain.*
import com.spaceboy.ridebuddy.ui.screens.LiveCardFixture
import com.spaceboy.ridebuddy.ui.screens.MoreSettingsActions
import kotlinx.coroutines.flow.MutableStateFlow

internal object AppUiFixture {
    val ride = Ride(1, System.currentTimeMillis() - 3_600_000L, System.currentTimeMillis(),
        28.6, 28.6, 82.0, 4500.0, 8000, 28.0, 1.1, startArea = "Home", endArea = "Marina Beach")
    val live = LiveTelemetryStreams(MutableStateFlow(LiveCardFixture.Frame), MutableStateFlow(BleDiagnostics()),
        MutableStateFlow(LiveCardFixture.recording()), MutableStateFlow(false),
        MutableStateFlow(emptyList()), MutableStateFlow(LiveRideMetrics()))
    fun state(ui: MainUiState) = MainScreenState(
        uiState = ui,
        connectionState = BikeConnectionState.Connected("Aprilia RS 457", -60),
        identity = BikeIdentity(vin = "TESTVIN123456789", clusterSoftwareVersion = "1.0"),
        bleCapture = BleCaptureState(),
        live = live,
        rides = listOf(ride),
        insights = RideInsights(rideCount = 3, totalDistanceKilometres = 142.6, totalDurationMillis = 14_520_000,
            averageSpeedKph = 35.4, averageRpm = 4560.0, averageThrottlePercent = 29.8,
            distanceTrendKilometres = listOf(28.6, 48.0, 66.0)),
        insightPeriod = InsightPeriod.ThirtyDays,
        guidance = GuidanceState(),
        settings = AppSettings(distanceUnits = DistanceUnits.Metric, dynamicColor = false, onboardingComplete = true),
        bikeAssociation = BikeAssociationState(supported = true,
            bike = AssociatedBike(requireNotNull(BluetoothAddress.fromLong(1)), "Aprilia RS 457"), observingPresence = true),
        notificationAccessEnabled = true,
        backgroundLocationGranted = true,
    )
    val settingsActions = MoreSettingsActions(
        onNotificationPackageChanged = { _, _ -> },
        onCallerDisplayChanged = { _ -> },
        onTftCallControlsChanged = { _ -> },
        onRideStartSpeedChanged = { _ -> },
        onRideStopSpeedChanged = { _ -> },
        onRideStopDelayChanged = { _ -> },
        onOverspeedAlertsChanged = { _ -> },
        onOverspeedThresholdChanged = { _ -> },
        onRpmAlertsChanged = { _ -> },
        onRpmThresholdChanged = { _ -> },
        onAccelerationAlertsChanged = { _ -> },
        onBrakingAlertsChanged = { _ -> },
        onWeatherAlertsChanged = { _ -> },
        onHazardAlertsChanged = { _ -> },
        onTftNavigationOutputChanged = { _ -> },
        onTftTextModeChanged = { _ -> },
        onSampleRetentionChanged = { _ -> },
        onThemeModeChanged = { _ -> },
        onDynamicColorChanged = { _ -> },
        onHighContrastChanged = { _ -> },
        onBleCaptureEnabledChanged = { _ -> },
        onPersistConnectionDiagnosticsChanged = { _ -> },
    )
    fun actions(onDestination: (TopLevelDestination) -> Unit = {}) = MainScreenActions(
        onDestinationSelected = onDestination,
        onOpenNavigationSettings = { },
        onCloseNavigationSettings = { },
        onOpenDiagnostics = { },
        onCloseDiagnostics = { },
        onSaveNavigationApiKey = { _ -> },
        onRemoveNavigationApiKey = { },
        onTestNavigationApiKey = { },
        onDisconnectBike = { },
        onEndRide = { },
        onRetryRideSave = { },
        onStartNavigation = { _ -> },
        onOpenActiveNavigation = { },
        onStopNavigation = { },
        onSharedDestinationHandled = { },
        onCancelNavigationStart = { },
        onInsightPeriodSelected = { _ -> },
        onClearRideHistory = { },
        onExportRideHistory = { },
        onOpenNotificationAccess = { },
        onAssociateBike = { },
        onForgetBike = { },
        onRideSelected = { _ -> },
        onDistanceUnitsChanged = { _ -> },
        onVoiceGuidanceChanged = { _ -> },
        onAvoidTollsChanged = { _ -> },
        onAvoidHighwaysChanged = { _ -> },
        onAvoidFerriesChanged = { _ -> },
        onAutoStartSharedChanged = { _ -> },
        onResetOnboarding = { },
        onExportDiagnostics = { },
        onExportBleCapture = { },
        onClearBleCapture = { },
        onRunStationaryTest = { },
        onOpenBackgroundLocationSettings = { },
        onOpenAppPermissions = { },
        onMessageShown = { },
        settingsActions = settingsActions,
    )
}
