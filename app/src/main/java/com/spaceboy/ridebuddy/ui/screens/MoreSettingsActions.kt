package com.spaceboy.ridebuddy.ui.screens

import com.spaceboy.ridebuddy.data.TftTextMode
import com.spaceboy.ridebuddy.data.ThemeMode

data class MoreSettingsActions(
    val onNotificationPackageChanged: (String, Boolean) -> Unit,
    val onCallerDisplayChanged: (Boolean) -> Unit,
    val onTftCallControlsChanged: (Boolean) -> Unit,
    val onRideStartSpeedChanged: (Double) -> Unit,
    val onRideStopSpeedChanged: (Double) -> Unit,
    val onRideStopDelayChanged: (Int) -> Unit,
    val onOverspeedAlertsChanged: (Boolean) -> Unit,
    val onOverspeedThresholdChanged: (Int) -> Unit,
    val onRpmAlertsChanged: (Boolean) -> Unit,
    val onRpmThresholdChanged: (Int) -> Unit,
    val onAccelerationAlertsChanged: (Boolean) -> Unit,
    val onBrakingAlertsChanged: (Boolean) -> Unit,
    val onWeatherAlertsChanged: (Boolean) -> Unit,
    val onHazardAlertsChanged: (Boolean) -> Unit,
    val onTftNavigationOutputChanged: (Boolean) -> Unit,
    val onTftTextModeChanged: (TftTextMode) -> Unit,
    val onThemeModeChanged: (ThemeMode) -> Unit,
    val onDynamicColorChanged: (Boolean) -> Unit,
    val onHighContrastChanged: (Boolean) -> Unit,
    val onBleCaptureEnabledChanged: (Boolean) -> Unit,
    val onPersistConnectionDiagnosticsChanged: (Boolean) -> Unit,
)
