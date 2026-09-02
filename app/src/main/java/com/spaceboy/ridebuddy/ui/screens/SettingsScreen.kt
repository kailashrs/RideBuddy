package com.spaceboy.ridebuddy.ui.screens

import android.content.pm.PackageManager
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.AltRoute
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.automirrored.outlined.PhoneCallback
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.BluetoothConnected
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.ColorLens
import androidx.compose.material.icons.outlined.ContactPage
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material.icons.outlined.Directions
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Mail
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.ReportProblem
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Straighten
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import android.widget.Toast
import com.spaceboy.ridebuddy.BuildConfig
import com.spaceboy.ridebuddy.NavigationKeyUiState
import com.spaceboy.ridebuddy.R
import com.spaceboy.ridebuddy.ble.BleCaptureState
import com.spaceboy.ridebuddy.core.companion.BikeAssociationState
import com.spaceboy.ridebuddy.data.AppSettings
import com.spaceboy.ridebuddy.data.DistanceUnits
import com.spaceboy.ridebuddy.data.SupportedNotificationApp
import com.spaceboy.ridebuddy.data.SupportedNotificationApps
import com.spaceboy.ridebuddy.data.TftTextMode
import com.spaceboy.ridebuddy.data.ThemeMode
import com.spaceboy.ridebuddy.data.UnitFormatter
import com.spaceboy.ridebuddy.ui.components.SettingsChoiceRow
import com.spaceboy.ridebuddy.ui.components.SettingsRow
import com.spaceboy.ridebuddy.ui.components.SettingsSection
import com.spaceboy.ridebuddy.ui.components.SettingsSliderRow
import com.spaceboy.ridebuddy.ui.components.SettingsSwitchRow
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Every setting, grouped into sections.
 *
 * Each section below is its own composable rather than one long list, so the file stays
 * navigable and each group's parameters make plain what it actually touches.
 *
 * Three groups need care. Anything that writes to the motorcycle — caller display, call
 * controls, navigation output — is off by default and carries a warning to validate it
 * parked first. Anything that captures data is off by default because captures can contain
 * identifiers and notification text. And the destructive actions (forget bike, clear
 * history) confirm before acting.
 */
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    navigationKey: NavigationKeyUiState,
    onOpenNavigationSettings: () -> Unit,
    bleCapture: BleCaptureState,
    rideCount: Int,
    onClearRideHistory: () -> Unit,
    onExportRideHistory: () -> Unit,
    onOpenNotificationAccess: () -> Unit,
    onEnableCallControls: () -> Unit,
    bikeAssociation: BikeAssociationState,
    onAssociateBike: () -> Unit,
    onForgetBike: () -> Unit,
    settings: AppSettings,
    onDistanceUnitsChanged: (DistanceUnits) -> Unit,
    onAutoStartSharedChanged: (Boolean) -> Unit,
    onMessageAlertsChanged: (Boolean) -> Unit,
    onSocialAlertsChanged: (Boolean) -> Unit,
    onEmailAlertsChanged: (Boolean) -> Unit,
    settingsActions: MoreSettingsActions,
    onResetOnboarding: () -> Unit,
    onExportDiagnostics: () -> Unit,
    onExportBleCapture: () -> Unit,
    onClearBleCapture: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onRunStationaryTest: () -> Unit,
    notificationAccessEnabled: Boolean,
    legacyCallPermissionGranted: Boolean,
    onLegacyCallControlsChanged: (Boolean) -> Unit,
    backgroundLocationGranted: Boolean,
    onOpenBackgroundLocationSettings: () -> Unit,
    onOpenAppPermissions: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var installedAppsRefresh by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) installedAppsRefresh++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val installedSupportedApps by produceState<List<SupportedNotificationApp>>(
        initialValue = emptyList(),
        context,
        installedAppsRefresh,
    ) {
        value = withContext(Dispatchers.IO) {
            SupportedNotificationApps.filter { app ->
                try {
                    context.packageManager.getPackageInfo(
                        app.packageName,
                        PackageManager.PackageInfoFlags.of(0),
                    )
                    true
                } catch (_: Exception) {
                    false
                }
            }
        }
    }
    val uriHandler = LocalUriHandler.current
    val locale = LocalConfiguration.current.locales[0]
    val speed = remember(settings.distanceUnits, locale) { SpeedFormat(settings.distanceUnits, locale) }
    var confirmTest by remember { mutableStateOf(false) }
    var confirmForget by remember { mutableStateOf(false) }
    var confirmClearRideHistory by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var showSupportedAppsDialog by remember { mutableStateOf(false) }
    var showBleCapture by remember { mutableStateOf(false) }
    if (confirmTest) {
        StationaryTestDialog(
            onDismiss = { confirmTest = false },
            onConfirm = { confirmTest = false; onRunStationaryTest() },
        )
    }
    if (confirmForget) {
        ForgetBikeDialog(
            onDismiss = { confirmForget = false },
            onConfirm = { confirmForget = false; onForgetBike() },
        )
    }
    if (confirmClearRideHistory) {
        ClearHistoryDialog(
            rideCount = rideCount,
            onDismiss = { confirmClearRideHistory = false },
            onConfirm = { confirmClearRideHistory = false; onClearRideHistory() },
        )
    }
    if (showAbout) {
        AboutDialog(onDismiss = { showAbout = false })
    }
    if (showSupportedAppsDialog) {
        SupportedAppsDialog(
            installedSupportedApps = installedSupportedApps,
            enabledPackages = settings.enabledNotificationPackages,
            onPackageChanged = settingsActions.onNotificationPackageChanged,
            onDismiss = { showSupportedAppsDialog = false },
        )
    }
    if (showBleCapture) {
        BleCaptureDialog(
            bleCapture = bleCapture,
            onExport = onExportBleCapture,
            onClear = onClearBleCapture,
            onDismiss = { showBleCapture = false },
        )
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item("connection") {
            MotorcycleConnectionSection(
                bikeAssociation = bikeAssociation,
                onAssociateBike = onAssociateBike,
                onRequestForget = { confirmForget = true },
            )
        }
        item("navigation") {
            NavigationAndCallsSection(
                settings = settings,
                settingsActions = settingsActions,
                navigationKey = navigationKey,
                notificationAccessEnabled = notificationAccessEnabled,
                legacyCallPermissionGranted = legacyCallPermissionGranted,
                backgroundLocationGranted = backgroundLocationGranted,
                installedSupportedApps = installedSupportedApps,
                onOpenNavigationSettings = onOpenNavigationSettings,
                onOpenNotificationAccess = onOpenNotificationAccess,
                onOpenBackgroundLocationSettings = onOpenBackgroundLocationSettings,
                onEnableCallControls = onEnableCallControls,
                onDistanceUnitsChanged = onDistanceUnitsChanged,
                onAutoStartSharedChanged = onAutoStartSharedChanged,
                onMessageAlertsChanged = onMessageAlertsChanged,
                onSocialAlertsChanged = onSocialAlertsChanged,
                onEmailAlertsChanged = onEmailAlertsChanged,
                onLegacyCallControlsChanged = onLegacyCallControlsChanged,
                onManageSupportedApps = { showSupportedAppsDialog = true },
            )
        }
        item("trip-tracking") {
            TripTrackingSection(settings, settingsActions, speed)
        }
        item("ride-data") {
            RideDataSection(
                rideCount = rideCount,
                onExportRideHistory = onExportRideHistory,
                onRequestClearHistory = { confirmClearRideHistory = true },
            )
        }
        item("safety-alerts") {
            SafetyAlertsSection(
                settings = settings,
                settingsActions = settingsActions,
                speed = speed,
                onOpenWeatherAttribution = {
                    runCatching { uriHandler.openUri(OpenMeteoUrl) }
                        .onFailure {
                            Toast.makeText(context, R.string.browser_unavailable, Toast.LENGTH_LONG).show()
                        }
                },
            )
        }
        item("motorcycle-display") {
            MotorcycleDisplaySection(settings, settingsActions)
        }
        item("app-theme") {
            AppThemeSection(settings, settingsActions)
        }
        item("permissions") {
            PermissionsSection(
                onOpenAppPermissions = onOpenAppPermissions,
                onShowAbout = { showAbout = true },
                onResetOnboarding = onResetOnboarding,
            )
        }
        item("developer-tools") {
            DeveloperToolsSection(
                settings = settings,
                settingsActions = settingsActions,
                bleCapture = bleCapture,
                onOpenDiagnostics = onOpenDiagnostics,
                onViewCapture = { showBleCapture = true },
                onRequestStationaryTest = { confirmTest = true },
            )
        }
    }
}

/**
 * Converts between the km/h the app stores and the value a slider shows in the rider's units.
 * Shared by the trip-tracking and alert sections, which both drive sliders in display units and
 * write back canonical ones.
 */
/**
 * Converts between stored speeds and the rider's display units for the slider rows.
 *
 * Speeds are stored in km/h but the sliders operate in whatever unit the rider has chosen,
 * so the conversion has to run both ways: [display] to place the handle, [stored] to
 * convert the position back before saving.
 */
@Immutable
private class SpeedFormat(private val units: DistanceUnits, private val locale: Locale) {
    fun display(kph: Double): Float = UnitFormatter.chartSpeed(kph, units).toFloat()
    fun stored(sliderValue: Float): Double = UnitFormatter.speedFromChart(sliderValue.toDouble(), units)
    fun label(kph: Double): String = UnitFormatter.speed(kph, units, locale)
}

private const val OpenMeteoUrl = "https://open-meteo.com/"

@Composable
private fun MotorcycleConnectionSection(
    bikeAssociation: BikeAssociationState,
    onAssociateBike: () -> Unit,
    onRequestForget: () -> Unit,
) {
    SettingsSection("Motorcycle Connection") {
        SettingsRow(
            icon = Icons.Outlined.Bluetooth,
            title = bikeAssociation.bike?.name ?: "Pair your motorcycle",
            supportingText = when {
                bikeAssociation.associationInProgress -> "Waiting for Bluetooth permission"
                bikeAssociation.bike == null && !bikeAssociation.supported -> "Companion device setup is unavailable on this phone"
                bikeAssociation.bike == null -> "Pair with nearby motorcycle via Bluetooth"
                bikeAssociation.observingPresence -> "Paired • automatic reconnection enabled"
                else -> "Paired • tap to enable automatic reconnection"
            },
            onClick = if (bikeAssociation.bike == null) onAssociateBike else null,
        )
        if (bikeAssociation.bike != null) {
            HorizontalDivider(Modifier.padding(start = 56.dp))
            SettingsRow(
                icon = Icons.Outlined.BluetoothConnected,
                title = "Bluetooth Pairing",
                supportingText = "${bikeAssociation.bike.address.takeLast(5)} • Paired via Bluetooth",
                trailingContent = { TextButton(onClick = onRequestForget) { Text("Forget") } },
            )
        }
    }
}

@Composable
private fun NavigationAndCallsSection(
    settings: AppSettings,
    settingsActions: MoreSettingsActions,
    navigationKey: NavigationKeyUiState,
    notificationAccessEnabled: Boolean,
    legacyCallPermissionGranted: Boolean,
    backgroundLocationGranted: Boolean,
    installedSupportedApps: List<SupportedNotificationApp>,
    onOpenNavigationSettings: () -> Unit,
    onOpenNotificationAccess: () -> Unit,
    onOpenBackgroundLocationSettings: () -> Unit,
    onEnableCallControls: () -> Unit,
    onDistanceUnitsChanged: (DistanceUnits) -> Unit,
    onAutoStartSharedChanged: (Boolean) -> Unit,
    onMessageAlertsChanged: (Boolean) -> Unit,
    onSocialAlertsChanged: (Boolean) -> Unit,
    onEmailAlertsChanged: (Boolean) -> Unit,
    onLegacyCallControlsChanged: (Boolean) -> Unit,
    onManageSupportedApps: () -> Unit,
) {
    SettingsSection("Navigation & Calls") {
        SettingsSwitchRow(
            title = "Use miles",
            supportingText = "Display distance and speed in imperial units",
            checked = settings.distanceUnits == DistanceUnits.Imperial,
            icon = Icons.Outlined.Straighten,
            onCheckedChange = { imperial -> onDistanceUnitsChanged(if (imperial) DistanceUnits.Imperial else DistanceUnits.Metric) },
        )
        HorizontalDivider(Modifier.padding(start = 56.dp))
        SettingsSwitchRow(
            title = "Start shared destinations",
            supportingText = "Begin navigation when a Google Maps destination is shared",
            checked = settings.autoStartSharedDestinations,
            icon = Icons.AutoMirrored.Outlined.AltRoute,
            onCheckedChange = onAutoStartSharedChanged,
        )
        HorizontalDivider(Modifier.padding(start = 56.dp))
        SettingsRow(
            icon = Icons.Outlined.Directions,
            title = "Navigation",
            supportingText = when {
                navigationKey.isLoading -> "Checking encrypted API key"
                navigationKey.maskedKey != null -> navigationKey.maskedKey
                else -> "API key not configured"
            },
            onClick = onOpenNavigationSettings,
        )
        HorizontalDivider(Modifier.padding(start = 56.dp))
        SettingsRow(
            icon = Icons.Outlined.LocationOn,
            title = "Navigation with screen off",
            supportingText = if (backgroundLocationGranted) {
                "Always-on location enabled for uninterrupted guidance"
            } else {
                "Optional: set Location to Allow all the time for the most accurate background guidance"
            },
            onClick = onOpenBackgroundLocationSettings,
        )
        HorizontalDivider(Modifier.padding(start = 56.dp))
        SettingsRow(
            icon = Icons.Outlined.Notifications,
            title = "Alerts & notifications",
            supportingText = if (notificationAccessEnabled) "Notification access enabled" else "Tap to enable notification access",
            onClick = onOpenNotificationAccess,
        )
        HorizontalDivider(Modifier.padding(start = 56.dp))
        SettingsSwitchRow(
            "Message alerts",
            "Messages and WhatsApp icons on the TFT",
            settings.messageAlerts,
            icon = Icons.AutoMirrored.Outlined.Chat,
            onCheckedChange = onMessageAlertsChanged
        )
        HorizontalDivider(Modifier.padding(start = 56.dp))
        SettingsSwitchRow(
            "Social alerts",
            "Instagram, Facebook and X icons on the TFT",
            settings.socialAlerts,
            icon = Icons.Outlined.Public,
            onCheckedChange = onSocialAlertsChanged
        )
        HorizontalDivider(Modifier.padding(start = 56.dp))
        SettingsSwitchRow(
            "Email alerts",
            "Gmail and Outlook icons on the TFT",
            settings.emailAlerts,
            icon = Icons.Outlined.Mail,
            onCheckedChange = onEmailAlertsChanged
        )
        HorizontalDivider(Modifier.padding(start = 56.dp))
        val enabledInstalledCount =
            installedSupportedApps.count { it.packageName in settings.enabledNotificationPackages }
        SettingsRow(
            icon = Icons.Outlined.Apps,
            title = "Supported apps",
            supportingText = if (installedSupportedApps.isEmpty()) {
                "No supported messaging or social apps detected on device"
            } else {
                "$enabledInstalledCount of ${installedSupportedApps.size} installed apps enabled"
            },
            onClick = onManageSupportedApps,
            trailingContent = { TextButton(onClick = onManageSupportedApps) { Text("Manage") } },
        )
        HorizontalDivider(Modifier.padding(start = 56.dp))
        SettingsRow(
            icon = Icons.Outlined.Settings,
            title = "Bike call controls",
            supportingText = if (notificationAccessEnabled) "Standard call actions enabled; your phone app remains in control" else "Tap to enable notification access; your default phone app is unchanged",
            onClick = onEnableCallControls,
        )
        HorizontalDivider(Modifier.padding(start = 56.dp))
        SettingsSwitchRow(
            "Caller display",
            "Show incoming caller name and number on the motorcycle display",
            settings.callerDisplay,
            icon = Icons.Outlined.ContactPage
        ) {
            settingsActions.onCallerDisplayChanged(it)
        }
        HorizontalDivider(Modifier.padding(start = 56.dp))
        SettingsSwitchRow(
            "TFT call controls",
            "Accept or decline incoming phone calls using handlebar controls",
            settings.tftCallControls,
            icon = Icons.Outlined.Call
        ) {
            settingsActions.onTftCallControlsChanged(it)
        }
        HorizontalDivider(Modifier.padding(start = 56.dp))
        SettingsSwitchRow(
            title = "Legacy call compatibility",
            supportingText = when {
                settings.legacyCallControls && legacyCallPermissionGranted -> "Fallback for dialers that do not publish standard call actions"
                settings.legacyCallControls -> "Phone-call permission must be restored"
                else -> "Legacy mode for non-standard phone dialer apps"
            },
            checked = settings.legacyCallControls && legacyCallPermissionGranted,
            icon = Icons.AutoMirrored.Outlined.PhoneCallback,
            onCheckedChange = onLegacyCallControlsChanged,
        )
    }
}

@Composable
private fun TripTrackingSection(
    settings: AppSettings,
    settingsActions: MoreSettingsActions,
    speed: SpeedFormat,
) {
    SettingsSection("Automatic Trip Tracking") {
        SettingsSliderRow(
            title = "Start above ${speed.label(settings.rideStartSpeedKph)}",
            supportingText = "A ride starts automatically after this speed",
            value = speed.display(settings.rideStartSpeedKph),
            range = speed.display(1.0)..speed.display(15.0),
            steps = 13,
            icon = Icons.Outlined.Speed,
            onValueChange = { value ->
                settingsActions.onRideStartSpeedChanged(
                    speed.stored(value).coerceAtLeast(settings.rideStopSpeedKph + 0.5),
                )
            },
        )
        HorizontalDivider(Modifier.padding(start = 56.dp))
        SettingsSliderRow(
            title = "Stop below ${speed.label(settings.rideStopSpeedKph)}",
            supportingText = "The bike must remain below this speed",
            value = speed.display(settings.rideStopSpeedKph),
            range = speed.display(0.0)..speed.display(10.0),
            steps = 9,
            icon = Icons.Outlined.Timer,
            onValueChange = { value ->
                settingsActions.onRideStopSpeedChanged(
                    speed.stored(value).coerceAtMost(settings.rideStartSpeedKph - 0.5),
                )
            },
        )
        HorizontalDivider(Modifier.padding(start = 56.dp))
        SettingsSliderRow(
            title = "Stop after ${settings.rideStopDelaySeconds / 60.0} min",
            supportingText = "Parking delay before the ride is saved",
            value = settings.rideStopDelaySeconds.toFloat(),
            range = 30f..300f,
            steps = 8,
            icon = Icons.Outlined.Schedule,
            onValueChange = { settingsActions.onRideStopDelayChanged(it.toInt()) },
        )
    }
}

@Composable
private fun SafetyAlertsSection(
    settings: AppSettings,
    settingsActions: MoreSettingsActions,
    speed: SpeedFormat,
    onOpenWeatherAttribution: () -> Unit,
) {
    SettingsSection("Safety & Speed Alerts") {
        SettingsSwitchRow(
            "Overspeed",
            "Alert above ${speed.label(settings.overspeedThresholdKph.toDouble())}",
            settings.overspeedAlerts,
            icon = Icons.Outlined.Speed
        ) {
            settingsActions.onOverspeedAlertsChanged(it)
        }
        if (settings.overspeedAlerts) {
            HorizontalDivider(Modifier.padding(start = 56.dp))
            SettingsSliderRow(
                title = "Overspeed threshold",
                supportingText = speed.label(settings.overspeedThresholdKph.toDouble()),
                value = speed.display(settings.overspeedThresholdKph.toDouble()),
                range = speed.display(40.0)..speed.display(200.0),
                steps = 15,
                icon = Icons.Outlined.Speed,
            ) { value -> settingsActions.onOverspeedThresholdChanged(speed.stored(value).roundToInt()) }
        }
        HorizontalDivider(Modifier.padding(start = 56.dp))
        SettingsSwitchRow(
            "High RPM",
            "Alert above ${settings.rpmThreshold} rpm",
            settings.rpmAlerts,
            icon = Icons.Outlined.Tune
        ) {
            settingsActions.onRpmAlertsChanged(it)
        }
        if (settings.rpmAlerts) {
            HorizontalDivider(Modifier.padding(start = 56.dp))
            SettingsSliderRow(
                title = "RPM threshold",
                supportingText = "${settings.rpmThreshold} rpm",
                value = settings.rpmThreshold.toFloat(),
                range = 3_000f..12_000f,
                steps = 17,
                icon = Icons.Outlined.Tune,
            ) { settingsActions.onRpmThresholdChanged(it.toInt()) }
        }
        HorizontalDivider(Modifier.padding(start = 56.dp))
        SettingsSwitchRow(
            "Hard acceleration",
            "Notify when acceleration exceeds the event threshold",
            settings.accelerationAlerts,
            icon = Icons.AutoMirrored.Outlined.TrendingUp
        ) {
            settingsActions.onAccelerationAlertsChanged(it)
        }
        HorizontalDivider(Modifier.padding(start = 56.dp))
        SettingsSwitchRow(
            "Hard braking",
            "Notify when braking exceeds the event threshold",
            settings.brakingAlerts,
            icon = Icons.Outlined.ReportProblem
        ) {
            settingsActions.onBrakingAlertsChanged(it)
        }
        HorizontalDivider(Modifier.padding(start = 56.dp))
        SettingsSwitchRow(
            "Weather",
            "Rain, storm and high-wind warnings on the phone and, when enabled, the TFT",
            settings.weatherAlerts,
            icon = Icons.Outlined.Cloud
        ) {
            settingsActions.onWeatherAlertsChanged(it)
        }
        if (settings.weatherAlerts) {
            TextButton(
                onClick = onOpenWeatherAttribution,
                modifier = Modifier.padding(start = 56.dp),
            ) {
                Text("Weather data by Open-Meteo.com")
            }
        }
        HorizontalDivider(Modifier.padding(start = 56.dp))
        SettingsSwitchRow(
            "Road disruptions & cameras",
            "Show Google incidents and cameras on the map, with supported route warnings on the TFT",
            settings.hazardAlerts,
            icon = Icons.Outlined.CameraAlt,
        ) {
            settingsActions.onHazardAlertsChanged(it)
        }
    }
}

@Composable
private fun MotorcycleDisplaySection(
    settings: AppSettings,
    settingsActions: MoreSettingsActions,
) {
    SettingsSection("Motorcycle Display") {
        SettingsSwitchRow(
            "TFT navigation output",
            "Show turn-by-turn maneuvers and distances on the motorcycle display",
            settings.tftNavigationOutputEnabled,
            icon = Icons.Outlined.Tv,
        ) { enabled -> settingsActions.onTftNavigationOutputChanged(enabled) }
        HorizontalDivider(Modifier.padding(start = 56.dp))
        SettingsChoiceRow(
            title = "Navigation text",
            choices = TftTextMode.entries,
            selectedChoice = settings.tftTextMode,
            icon = Icons.Outlined.TextFields,
            choiceLabel = TftTextMode::name,
            onSelected = settingsActions.onTftTextModeChanged,
        )
        Text(
            "Extreme-weather and supported route warnings briefly use the navigation text rows. Calls and approaching turns always take priority.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 56.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
        )
    }
}

@Composable
private fun AppThemeSection(
    settings: AppSettings,
    settingsActions: MoreSettingsActions,
) {
    SettingsSection("App Theme & Display") {
        SettingsChoiceRow(
            title = "Theme",
            choices = ThemeMode.entries,
            selectedChoice = settings.themeMode,
            icon = Icons.Outlined.Palette,
            choiceLabel = ThemeMode::name,
            onSelected = settingsActions.onThemeModeChanged,
        )
        HorizontalDivider(Modifier.padding(start = 56.dp))
        SettingsSwitchRow(
            "Dynamic color",
            "Use your device's Material You palette",
            settings.dynamicColor,
            icon = Icons.Outlined.ColorLens
        ) {
            settingsActions.onDynamicColorChanged(it)
        }
        HorizontalDivider(Modifier.padding(start = 56.dp))
        SettingsSwitchRow(
            "High contrast",
            "Use stronger surface and text contrast",
            settings.highContrast,
            icon = Icons.Outlined.Contrast
        ) {
            settingsActions.onHighContrastChanged(it)
        }
    }
}

/**
 * Diagnostics and validation tools.
 *
 * Includes the parked display test, which is the intended way to verify vehicle output
 * before enabling it for real, and the packet capture, whose privacy note is part of the
 * dialog rather than buried in this section.
 */
@Composable
private fun DeveloperToolsSection(
    settings: AppSettings,
    settingsActions: MoreSettingsActions,
    bleCapture: BleCaptureState,
    onOpenDiagnostics: () -> Unit,
    onViewCapture: () -> Unit,
    onRequestStationaryTest: () -> Unit,
) {
    SettingsSection("Developer tools") {
        SettingsRow(
            icon = Icons.Outlined.Info,
            title = "Use only while parked",
            supportingText = "For protocol research and troubleshooting. Raw Bluetooth payloads may " +
                "include personal data; capture only what you intend to inspect or share.",
        )
        HorizontalDivider()
        DeveloperToolsGroupLabel("Connection")
        SettingsRow(
            icon = Icons.Outlined.Bluetooth,
            title = "Connection details",
            supportingText = "Connection state, GATT services, and recent activity",
            onClick = onOpenDiagnostics,
        )
        HorizontalDivider(Modifier.padding(start = 56.dp))
        SettingsSwitchRow(
            title = "Save diagnostic history",
            supportingText = if (settings.persistConnectionDiagnostics) {
                "Recent connection activity is saved across app restarts"
            } else {
                "Off — connection activity is kept only for this app session"
            },
            icon = Icons.Outlined.History,
            checked = settings.persistConnectionDiagnostics,
            onCheckedChange = settingsActions.onPersistConnectionDiagnosticsChanged,
        )
        HorizontalDivider()
        DeveloperToolsGroupLabel("Protocol capture")
        SettingsSwitchRow(
            title = "Capture BLE traffic",
            supportingText = if (bleCapture.enabled) {
                "Capturing raw GATT reads, notifications, and writes in memory"
            } else {
                "Off — enable before reproducing the behavior you want to inspect"
            },
            icon = Icons.Outlined.BugReport,
            checked = settings.bleCaptureEnabled,
            onCheckedChange = settingsActions.onBleCaptureEnabledChanged,
        )
        HorizontalDivider(Modifier.padding(start = 56.dp))
        SettingsRow(
            icon = Icons.AutoMirrored.Outlined.ReceiptLong,
            title = "Review captured packets",
            supportingText = when {
                bleCapture.entries.isEmpty() -> "No packets captured"
                bleCapture.droppedEntries > 0 ->
                    "${bleCapture.entries.size} kept • ${bleCapture.droppedEntries} older packets dropped"
                else -> "${bleCapture.entries.size} packets kept in memory"
            },
            trailingContent = { TextButton(onClick = onViewCapture) { Text("View") } },
        )
        HorizontalDivider()
        DeveloperToolsGroupLabel("Display validation")
        SettingsRow(
            icon = Icons.Outlined.Tv,
            title = "Stationary TFT validation",
            supportingText = "Send diagnostic navigation and caller writes while parked.",
            trailingContent = { TextButton(onClick = onRequestStationaryTest) { Text("Run") } },
        )
    }
}

@Composable
private fun PermissionsSection(
    onOpenAppPermissions: () -> Unit,
    onShowAbout: () -> Unit,
    onResetOnboarding: () -> Unit,
) {
    SettingsSection("Permissions & System") {
        SettingsRow(
            icon = Icons.Outlined.Security,
            title = "Permissions & privacy",
            supportingText = "Bluetooth and location are requested only when needed",
            onClick = onOpenAppPermissions,
        )
        HorizontalDivider(Modifier.padding(start = 56.dp))
        SettingsRow(
            icon = Icons.Outlined.Info,
            title = "About",
            supportingText = "RideBuddy ${BuildConfig.VERSION_NAME}",
            onClick = { onShowAbout() },
        )
        HorizontalDivider(Modifier.padding(start = 56.dp))
        SettingsRow(
            icon = Icons.Outlined.RestartAlt,
            title = "Run setup again",
            supportingText = "Review permissions, pairing, and navigation",
            onClick = onResetOnboarding,
        )
    }
}

@Composable
private fun RideDataSection(
    rideCount: Int,
    onExportRideHistory: () -> Unit,
    onRequestClearHistory: () -> Unit,
) {
    SettingsSection("Ride Data & Export") {
        SettingsRow(
            icon = Icons.Outlined.History,
            title = "Ride history",
            supportingText = "$rideCount saved rides stored on this device",
            trailingContent = {
                TextButton(onClick = onRequestClearHistory, enabled = rideCount > 0) { Text("Clear") }
            },
        )
        HorizontalDivider(Modifier.padding(start = 56.dp))
        SettingsRow(
            icon = Icons.Outlined.FileDownload,
            title = "Export ride history",
            supportingText = "Share a CSV summary of every saved ride",
            onClick = onExportRideHistory,
        )
    }
}

@Composable
private fun DeveloperToolsGroupLabel(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp, end = 16.dp),
    )
}


@Composable
private fun StationaryTestDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { onDismiss() },
        title = { Text("Test the TFT?") },
        text = {
            Text(
                "Keep the motorcycle stationary and make sure no real call is in progress. The " +
                    "test writes a maneuver, trip distance, text and speed limit, then shows a " +
                    "test caller ringing, answered, cleared and outgoing.",
            )
        },
        confirmButton = {
            TextButton(onClick = {
                onDismiss(); onConfirm()
            }) { Text("Run test") }
        },
        dismissButton = { TextButton(onClick = { onDismiss() }) { Text("Cancel") } },
    )
}

@Composable
private fun ForgetBikeDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { onDismiss() },
        title = { Text("Forget this bike?") },
        text = { Text("The app will stop reconnecting automatically. You can pair the bike again at any time.") },
        confirmButton = {
            Button(
                onClick = { onDismiss(); onConfirm() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            ) { Text("Forget") }
        },
        dismissButton = { TextButton(onClick = { onDismiss() }) { Text("Cancel") } },
    )
}

@Composable
private fun ClearHistoryDialog(
    rideCount: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { onDismiss() },
        title = { Text("Clear all ride history?") },
        text = { Text("This permanently deletes $rideCount saved rides, their telemetry samples, route data, and performance records from this device. Export anything you want to keep first.") },
        confirmButton = {
            Button(
                onClick = {
                    onDismiss()
                    onConfirm()
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            ) { Text("Delete history") }
        },
        dismissButton = { TextButton(onClick = { onDismiss() }) { Text("Cancel") } },
    )
}

@Composable
private fun AboutDialog(
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { onDismiss() },
        title = { Text("About RideBuddy") },
        text = {
            Text(
                "Version ${BuildConfig.VERSION_NAME}\n\n" +
                        "RideBuddy is a third-party companion app for your motorcycle.\n\n" +
                        "It brings turn-by-turn navigation, speed limits, incoming call controls, and safety alerts directly to your motorcycle's display, while automatically logging your rides and trip statistics privately on your phone.",
            )
        },
        confirmButton = { TextButton(onClick = { onDismiss() }) { Text("Close") } },
    )
}

@Composable
private fun SupportedAppsDialog(
    installedSupportedApps: List<SupportedNotificationApp>,
    enabledPackages: Set<String>,
    onPackageChanged: (String, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = { onDismiss() }) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
            ) {
                Text(
                    "Supported notification apps",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Manage notification alerts for messaging and social apps installed on your device.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (installedSupportedApps.isEmpty()) {
                        Text(
                            "No supported apps (WhatsApp, Messages, Instagram, Facebook, Gmail, Outlook, X) were detected on this device.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        installedSupportedApps.forEach { app ->
                            val enabled = app.packageName in enabledPackages
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .toggleable(
                                        value = enabled,
                                        role = Role.Switch,
                                        onValueChange = { value ->
                                            onPackageChanged(app.packageName, value)
                                        },
                                    )
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        app.label,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        app.packageName,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = enabled,
                                    onCheckedChange = null,
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = { onDismiss() }) {
                        Text("Done")
                    }
                }
            }
        }
    }
}

/**
 * Shows the captured packets, newest first, with share and clear.
 *
 * The privacy note is shown here rather than only at the toggle, because this is the point
 * at which the rider is about to share the contents somewhere.
 */
@Composable
private fun BleCaptureDialog(
    bleCapture: BleCaptureState,
    onExport: () -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { onDismiss() },
        title = { Text("BLE capture") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Raw GATT packets can include identifiers and notification text. The capture stays in memory until cleared or the app closes.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (bleCapture.entries.isEmpty()) {
                    Text("No packets captured yet. Enable capture before connecting or using a feature.")
                } else {
                    bleCapture.entries.asReversed().forEach { entry ->
                        Text(entry.format(), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onExport, enabled = bleCapture.entries.isNotEmpty()) { Text("Share") }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onClear, enabled = bleCapture.entries.isNotEmpty()) { Text("Clear") }
                TextButton(onClick = { onDismiss() }) { Text("Close") }
            }
        },
    )
}
