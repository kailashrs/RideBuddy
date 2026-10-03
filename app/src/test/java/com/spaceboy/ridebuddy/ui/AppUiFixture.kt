package com.spaceboy.ridebuddy.ui

import com.spaceboy.ridebuddy.MainUiState
import com.spaceboy.ridebuddy.TopLevelDestination
import com.spaceboy.ridebuddy.ble.BleCaptureState
import android.net.MacAddress
import com.spaceboy.ridebuddy.core.companion.AssociatedBike
import com.spaceboy.ridebuddy.core.companion.BikeAssociationState
import com.spaceboy.ridebuddy.core.navigation.GuidanceState
import com.spaceboy.ridebuddy.data.*
import com.spaceboy.ridebuddy.data.db.Destination
import com.spaceboy.ridebuddy.domain.*
import com.spaceboy.ridebuddy.ui.screens.LiveCardFixture
import com.spaceboy.ridebuddy.ui.screens.MoreSettingsActions
import kotlinx.coroutines.flow.MutableStateFlow

internal object AppUiFixture {
    private val now = System.currentTimeMillis()

    /** Two frequent places, a third and a fourth that the card leaves out, and two saved ones. */
    val destinations = listOf(
        Destination(1, 13.0827, 80.2707, "Phoenix Marketcity", tripCount = 14, lastTripAtMillis = now - 2 * 86_400_000L),
        Destination(2, 13.0500, 80.2824, "Marina Beach", tripCount = 6, lastTripAtMillis = now - 9 * 86_400_000L),
        Destination(3, 13.0067, 80.2206, "12 Anna Salai", tripCount = 2, lastTripAtMillis = now - 3_600_000L),
        Destination(4, 12.9716, 80.2209, "Velachery", tripCount = 1, lastTripAtMillis = now - 20 * 86_400_000L),
        Destination(5, 13.0012, 80.2565, "4 Beach Road, Besant Nagar", savedName = "Home", tripCount = 30, lastTripAtMillis = now),
        Destination(6, 13.0418, 80.2341, "Express Avenue", savedName = "Mum's place"),
    )

    val ride = Ride(1, System.currentTimeMillis() - 3_600_000L, System.currentTimeMillis(),
        28.6, 28.6, 82.0, 4500.0, 8000, 28.0, 1.1, startArea = "Home", endArea = "Marina Beach")
    val live = LiveTelemetryStreams(MutableStateFlow(LiveCardFixture.Frame), MutableStateFlow(BleDiagnostics()),
        MutableStateFlow(LiveCardFixture.recording()),
        MutableStateFlow(emptyList()), MutableStateFlow(LiveRideMetrics()), MutableStateFlow(0.12))
    fun state(ui: MainUiState) = MainScreenState(
        uiState = ui,
        connectionState = BikeConnectionState.Connected("Aprilia RS 457", -60),
        identity = BikeIdentity(vin = "TESTVIN123456789", clusterSoftwareVersion = "1.0"),
        bleCapture = BleCaptureState(),
        live = live,
        rides = listOf(ride),
        trips = emptyList(),
        insights = RideInsights(rideCount = 3, totalDistanceKilometres = 142.6, totalDurationMillis = 14_520_000,
            averageSpeedKph = 35.4, averageRpm = 4560.0, averageThrottlePercent = 29.8,
            distanceTrendKilometres = listOf(28.6, 48.0, 66.0)),
        insightPeriod = InsightPeriod.ThisMonth,
        guidance = GuidanceState(),
        destinations = destinations,
        settings = AppSettings(distanceUnits = DistanceUnits.Metric, dynamicColor = false, onboardingComplete = true),
        bikeAssociation = BikeAssociationState(bike = AssociatedBike(MacAddress.fromString("00:00:00:00:00:01"), "Aprilia RS 457", associationId = 1), observingPresence = true),
        backgroundLocationGranted = true,
    )
    val settingsActions = MoreSettingsActions(
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
        onNavigateTo = { _ -> },
        onOpenGoogleMaps = { },
        onRenameDestination = { _, _ -> },
        onDeleteDestination = { _ -> },
        onClearRecentDestinations = { },
        onRetryShare = { },
        onOpenActiveNavigation = { },
        onStopNavigation = { },
        onCancelNavigationStart = { },
        onInsightPeriodSelected = { _ -> },
        onClearRideHistory = { },
        onExportRideHistory = { },
        onAssociateBike = { },
        onForgetBike = { },
        onRideSelected = { _ -> },
        onTripSelected = { _ -> },
        onSaveTrip = { _, _, _ -> },
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
        onOpenAppPermissions = { },
        onMessageShown = { },
        settingsActions = settingsActions,
    )
}
