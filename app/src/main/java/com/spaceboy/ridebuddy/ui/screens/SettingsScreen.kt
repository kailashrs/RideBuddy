package com.spaceboy.ridebuddy.ui.screens

import android.content.pm.PackageManager
import android.os.PowerManager
import android.provider.Telephony
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.AltRoute
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.ColorLens
import androidx.compose.material.icons.outlined.ContactPage
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DeveloperMode
import androidx.compose.material.icons.outlined.Directions
import androidx.compose.material.icons.outlined.DirectionsBoat
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.ReportProblem
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Straighten
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Toll
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.spaceboy.ridebuddy.BuildConfig
import com.spaceboy.ridebuddy.R
import com.spaceboy.ridebuddy.data.DistanceUnits
import com.spaceboy.ridebuddy.data.SampleRetention
import com.spaceboy.ridebuddy.data.SupportedNotificationApp
import com.spaceboy.ridebuddy.data.SupportedNotificationApps
import com.spaceboy.ridebuddy.data.TftTextMode
import com.spaceboy.ridebuddy.data.ThemeMode
import com.spaceboy.ridebuddy.data.UnitFormatter
import com.spaceboy.ridebuddy.data.defaultSmsNotificationApp
import com.spaceboy.ridebuddy.domain.BikeConnectionState
import com.spaceboy.ridebuddy.ui.MainScreenActions
import com.spaceboy.ridebuddy.ui.MainScreenState
import com.spaceboy.ridebuddy.ui.components.SectionHeader
import com.spaceboy.ridebuddy.ui.components.SettingsPickerRow
import com.spaceboy.ridebuddy.ui.components.SettingsRow
import com.spaceboy.ridebuddy.ui.components.SettingsSliderRow
import com.spaceboy.ridebuddy.ui.components.SettingsSwitchRow
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Every setting, as one Material list under subheaders.
 *
 * Anything that writes to the motorcycle — caller display, call controls, navigation output —
 * is off by default. Destructive actions confirm first. Developer tools live on their own
 * screen, one row away, so the riding settings are not buried under protocol switches.
 */
@Composable
fun SettingsScreen(
    state: MainScreenState,
    actions: MainScreenActions,
    modifier: Modifier = Modifier,
) {
    val settings = state.settings
    val settingsActions = actions.settingsActions
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val speed = remember(settings.distanceUnits, locale) { SpeedFormat(settings.distanceUnits, locale) }
    val uriHandler = LocalUriHandler.current

    // Re-read on resume: these are changed in system settings, which do not notify.
    var resumeCount by remember { mutableIntStateOf(0) }
    var unrestrictedBattery by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        resumeCount++
        unrestrictedBattery = context.getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(context.packageName) == true
    }
    val installedApps by produceState(emptyList<SupportedNotificationApp>(), context, resumeCount) {
        value = withContext(Dispatchers.IO) { installedSupportedApps(context) }
    }

    var dialog by rememberSaveable { mutableStateOf<SettingsDialog?>(null) }
    when (dialog) {
        SettingsDialog.ForgetBike -> ConfirmDialog(
            title = "Forget this bike?",
            text = "RideBuddy will stop reconnecting automatically. You can pair the bike again at any time.",
            confirm = "Forget",
            onDismiss = { dialog = null },
            onConfirm = actions.onForgetBike,
        )
        SettingsDialog.ClearHistory -> ConfirmDialog(
            title = "Delete all rides?",
            text = "This permanently deletes ${state.rides.size} rides with their routes and stats. Export anything you want to keep first.",
            confirm = "Delete",
            onDismiss = { dialog = null },
            onConfirm = actions.onClearRideHistory,
        )
        SettingsDialog.SupportedApps -> SupportedAppsDialog(
            installedApps = installedApps,
            disabledPackages = settings.disabledNotificationPackages,
            onPackageChanged = settingsActions.onNotificationPackageChanged,
            onDismiss = { dialog = null },
        )
        SettingsDialog.About -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("RideBuddy ${BuildConfig.VERSION_NAME}") },
            text = {
                Text(
                    "A third-party companion for your motorcycle. It brings turn-by-turn navigation, " +
                        "incoming calls and riding alerts to the bike's display, and keeps a private log " +
                        "of your rides on this phone.",
                )
            },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text("Close") } },
        )
        null -> Unit
    }

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        section("Motorcycle") {
            val bike = state.bikeAssociation.bike
            if (bike == null) {
                SettingsRow(
                    title = "Pair your motorcycle",
                    supportingText = when {
                        state.bikeAssociation.associationInProgress -> "Waiting for permission"
                        !state.bikeAssociation.supported -> "Pairing isn't supported on this phone"
                        else -> "Choose your bike from the list"
                    },
                    icon = Icons.Outlined.Bluetooth,
                    enabled = state.bikeAssociation.supported && !state.bikeAssociation.associationInProgress,
                    onClick = actions.onAssociateBike,
                )
            } else {
                SettingsRow(
                    title = bike.name,
                    supportingText = if (state.bikeAssociation.observingPresence) "Connects automatically" else "Automatic connection off",
                    icon = Icons.Outlined.Bluetooth,
                    trailingContent = { TextButton(onClick = { dialog = SettingsDialog.ForgetBike }) { Text("Forget") } },
                )
                val missing = if (state.connectionState is BikeConnectionState.Connected) "Not reported" else "Connect to read"
                SettingsRow("VIN", state.identity.vin ?: missing, icon = Icons.Outlined.Fingerprint)
                SettingsRow("Cluster software", state.identity.clusterSoftwareVersion ?: missing, icon = Icons.Outlined.Memory)
            }
        }
        section("Navigation") {
            SettingsRow(
                title = "Google Navigation key",
                supportingText = when {
                    state.uiState.navigationKey.isLoading -> "Checking…"
                    state.uiState.navigationKey.maskedKey != null -> state.uiState.navigationKey.maskedKey
                    else -> "Not set up"
                },
                icon = Icons.Outlined.Key,
                onClick = actions.onOpenNavigationSettings,
            )
            SettingsSwitchRow("Voice guidance", "Speak instructions on the phone", settings.voiceGuidance,
                icon = Icons.Outlined.RecordVoiceOver, onCheckedChange = actions.onVoiceGuidanceChanged)
            SettingsSwitchRow("Avoid tolls", null, settings.avoidTolls,
                icon = Icons.Outlined.Toll, onCheckedChange = actions.onAvoidTollsChanged)
            SettingsSwitchRow("Avoid highways", null, settings.avoidHighways,
                icon = Icons.AutoMirrored.Outlined.AltRoute, onCheckedChange = actions.onAvoidHighwaysChanged)
            SettingsSwitchRow("Avoid ferries", null, settings.avoidFerries,
                icon = Icons.Outlined.DirectionsBoat, onCheckedChange = actions.onAvoidFerriesChanged)
            SettingsSwitchRow("Start shared routes right away", "Skip the route preview when the bike is connected",
                settings.autoStartSharedDestinations, icon = Icons.Outlined.Directions,
                onCheckedChange = actions.onAutoStartSharedChanged)
        }
        section("Motorcycle display") {
            SettingsSwitchRow("Directions on the bike", "Show turns and distances on the display",
                settings.tftNavigationOutputEnabled, icon = Icons.Outlined.Tv,
                onCheckedChange = settingsActions.onTftNavigationOutputChanged)
            SettingsPickerRow("Direction text", TftTextMode.entries, settings.tftTextMode,
                icon = Icons.Outlined.TextFields, choiceLabel = { if (it == TftTextMode.Full) "Full" else "Compact" },
                onSelected = settingsActions.onTftTextModeChanged)
            SettingsSwitchRow("Caller display", "Show incoming calls on the bike", settings.callerDisplay,
                icon = Icons.Outlined.ContactPage, onCheckedChange = settingsActions.onCallerDisplayChanged)
            SettingsSwitchRow("Handlebar call controls", "Answer or decline calls from the handlebar",
                settings.tftCallControls, icon = Icons.Outlined.Call, onCheckedChange = settingsActions.onTftCallControlsChanged)
            SettingsRow(
                title = "App notifications",
                supportingText = when {
                    !state.notificationAccessEnabled -> "Notification access is off"
                    installedApps.isEmpty() -> "No supported apps installed"
                    else -> "${installedApps.count { it.packageName !in settings.disabledNotificationPackages }} of ${installedApps.size} apps"
                },
                icon = Icons.Outlined.Apps,
                onClick = { if (state.notificationAccessEnabled) dialog = SettingsDialog.SupportedApps else actions.onOpenNotificationAccess() },
            )
        }
        section("Riding alerts") {
            SettingsSwitchRow("Overspeed", "Alert above ${speed.label(settings.overspeedThresholdKph.toDouble())}",
                settings.overspeedAlerts, icon = Icons.Outlined.Speed, onCheckedChange = settingsActions.onOverspeedAlertsChanged)
            if (settings.overspeedAlerts) {
                SettingsSliderRow(
                    title = "Overspeed limit",
                    valueLabel = { speed.label(speed.stored(it)) },
                    value = speed.display(settings.overspeedThresholdKph.toDouble()),
                    range = speed.display(40.0)..speed.display(200.0),
                    steps = 15,
                ) { settingsActions.onOverspeedThresholdChanged(speed.stored(it).roundToInt()) }
            }
            SettingsSwitchRow("High RPM", "Alert above ${settings.rpmThreshold} rpm", settings.rpmAlerts,
                icon = Icons.Outlined.Tune, onCheckedChange = settingsActions.onRpmAlertsChanged)
            if (settings.rpmAlerts) {
                SettingsSliderRow(
                    title = "RPM limit",
                    valueLabel = { "${it.roundToInt()} rpm" },
                    value = settings.rpmThreshold.toFloat(),
                    range = 3_000f..12_000f,
                    steps = 17,
                ) { settingsActions.onRpmThresholdChanged(it.roundToInt()) }
            }
            SettingsSwitchRow("Hard acceleration", null, settings.accelerationAlerts,
                icon = Icons.AutoMirrored.Outlined.TrendingUp, onCheckedChange = settingsActions.onAccelerationAlertsChanged)
            SettingsSwitchRow("Hard braking", null, settings.brakingAlerts,
                icon = Icons.Outlined.ReportProblem, onCheckedChange = settingsActions.onBrakingAlertsChanged)
            SettingsSwitchRow("Weather", "Rain, storm and strong wind warnings. Data by Open-Meteo.com",
                settings.weatherAlerts, icon = Icons.Outlined.Cloud, onCheckedChange = settingsActions.onWeatherAlertsChanged)
            if (settings.weatherAlerts) {
                TextButton(
                    onClick = {
                        runCatching { uriHandler.openUri(OpenMeteoUrl) }
                            .onFailure { Toast.makeText(context, R.string.browser_unavailable, Toast.LENGTH_LONG).show() }
                    },
                    modifier = Modifier.padding(start = 44.dp),
                ) { Text("About Open-Meteo") }
            }
            SettingsSwitchRow("Road disruptions & cameras", "Incidents and cameras on the map, and route warnings on the bike",
                settings.hazardAlerts, icon = Icons.Outlined.CameraAlt, onCheckedChange = settingsActions.onHazardAlertsChanged)
        }
        section("Ride recording") {
            SettingsSliderRow(
                title = "Start recording above",
                valueLabel = { speed.label(speed.stored(it)) },
                value = speed.display(settings.rideStartSpeedKph),
                range = speed.display(1.0)..speed.display(15.0),
                steps = 13,
                icon = Icons.Outlined.Speed,
            ) { settingsActions.onRideStartSpeedChanged(speed.stored(it).coerceAtLeast(settings.rideStopSpeedKph + 0.5)) }
            SettingsSliderRow(
                title = "Stop recording below",
                valueLabel = { speed.label(speed.stored(it)) },
                value = speed.display(settings.rideStopSpeedKph),
                range = speed.display(0.0)..speed.display(10.0),
                steps = 9,
                icon = Icons.Outlined.Timer,
            ) { settingsActions.onRideStopSpeedChanged(speed.stored(it).coerceAtMost(settings.rideStartSpeedKph - 0.5)) }
            SettingsSliderRow(
                title = "End ride after parking for",
                valueLabel = { formatSeconds(it.roundToInt()) },
                value = settings.rideStopDelaySeconds.toFloat(),
                range = 30f..300f,
                steps = 8,
                icon = Icons.Outlined.Schedule,
            ) { settingsActions.onRideStopDelayChanged(it.roundToInt()) }
            SettingsRow(
                title = "Background location",
                supportingText = if (state.backgroundLocationGranted) "Allowed all the time" else "Needed to map rides with the screen off",
                icon = Icons.Outlined.LocationOn,
                onClick = actions.onOpenAppPermissions,
            )
        }
        section("Ride data") {
            SettingsPickerRow("Keep detailed ride data", SampleRetention.entries, settings.sampleRetention,
                icon = Icons.Outlined.Storage, choiceLabel = SampleRetention::label,
                onSelected = settingsActions.onSampleRetentionChanged)
            SettingsRow("Export all rides", "A CSV summary of every saved ride",
                icon = Icons.Outlined.FileDownload, enabled = state.rides.isNotEmpty(), onClick = actions.onExportRideHistory)
            SettingsRow("Delete all rides", "${state.rides.size} rides on this phone",
                icon = Icons.Outlined.DeleteOutline, enabled = state.rides.isNotEmpty(),
                onClick = { dialog = SettingsDialog.ClearHistory })
        }
        section("App") {
            SettingsSwitchRow("Use miles", "Distances and speeds in imperial units",
                settings.distanceUnits == DistanceUnits.Imperial, icon = Icons.Outlined.Straighten) { imperial ->
                actions.onDistanceUnitsChanged(if (imperial) DistanceUnits.Imperial else DistanceUnits.Metric)
            }
            SettingsPickerRow("Theme", ThemeMode.entries, settings.themeMode, icon = Icons.Outlined.Palette,
                choiceLabel = { if (it == ThemeMode.System) "System default" else it.name },
                onSelected = settingsActions.onThemeModeChanged)
            SettingsSwitchRow("Dynamic color", "Match your wallpaper's colors", settings.dynamicColor,
                icon = Icons.Outlined.ColorLens, onCheckedChange = settingsActions.onDynamicColorChanged)
            SettingsSwitchRow("High contrast", null, settings.highContrast,
                icon = Icons.Outlined.Contrast, onCheckedChange = settingsActions.onHighContrastChanged)
            SettingsRow("Notification access", if (state.notificationAccessEnabled) "Allowed" else "Needed for bike alerts",
                icon = Icons.Outlined.Notifications, onClick = actions.onOpenNotificationAccess)
            SettingsRow("Battery use", if (unrestrictedBattery) "Unrestricted" else "Optimized — may delay reconnecting",
                icon = Icons.Outlined.BatteryAlert, onClick = actions.onOpenAppPermissions)
            SettingsRow("App permissions", null, icon = Icons.Outlined.Security, onClick = actions.onOpenAppPermissions)
            SettingsRow("Run setup again", null, icon = Icons.Outlined.RestartAlt, onClick = actions.onResetOnboarding)
            SettingsRow("Developer tools", "Connection details, packet capture and display tests",
                icon = Icons.Outlined.DeveloperMode, onClick = actions.onOpenDiagnostics)
            SettingsRow("About", "Version ${BuildConfig.VERSION_NAME}", icon = Icons.Outlined.Info,
                onClick = { dialog = SettingsDialog.About })
        }
    }
}

private enum class SettingsDialog { ForgetBike, ClearHistory, SupportedApps, About }

private fun LazyListScope.section(title: String, content: @Composable () -> Unit) {
    item(key = title) {
        Column {
            SectionHeader(title, Modifier.padding(horizontal = 16.dp))
            content()
        }
    }
}

/** A destructive confirmation. The confirm action is error-coloured and closes the dialog. */
@Composable
internal fun ConfirmDialog(
    title: String,
    text: String,
    confirm: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(
                onClick = { onDismiss(); onConfirm() },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SupportedAppsDialog(
    installedApps: List<SupportedNotificationApp>,
    disabledPackages: Set<String>,
    onPackageChanged: (String, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("App notifications") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Choose which apps can show notifications on the bike.")
                if (installedApps.isEmpty()) {
                    Text(
                        "None of the supported apps (WhatsApp, Messages, Instagram, Facebook, Gmail, Outlook, X) are installed.",
                        Modifier.padding(top = 16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                installedApps.forEach { app ->
                    SettingsSwitchRow(app.label, null, app.packageName !in disabledPackages) {
                        onPackageChanged(app.packageName, it)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

/** The default SMS app first (resolved, so always installed), then the listed apps that are. */
private fun installedSupportedApps(context: android.content.Context): List<SupportedNotificationApp> {
    val packages = context.packageManager
    val defaultSms = runCatching {
        val packageName = Telephony.Sms.getDefaultSmsPackage(context)
        defaultSmsNotificationApp(packageName, packageName?.let {
            packages.getApplicationLabel(packages.getApplicationInfo(it, PackageManager.ApplicationInfoFlags.of(0))).toString()
        })
    }.getOrNull()
    return listOfNotNull(defaultSms) + SupportedNotificationApps.filter { app ->
        app.packageName != defaultSms?.packageName &&
            runCatching { packages.getPackageInfo(app.packageName, PackageManager.PackageInfoFlags.of(0)) }.isSuccess
    }
}

private fun formatSeconds(total: Int): String = when {
    total < 60 -> "$total s"
    total % 60 == 0 -> "${total / 60} min"
    else -> "${total / 60} min ${total % 60} s"
}

/**
 * Speeds are stored in km/h but sliders run in the rider's units, so the conversion goes both
 * ways: [display] places the handle and [stored] converts the position back before saving.
 */
@Immutable
private class SpeedFormat(private val units: DistanceUnits, private val locale: Locale) {
    fun display(kph: Double): Float = UnitFormatter.chartSpeed(kph, units).toFloat()
    fun stored(sliderValue: Float): Double = UnitFormatter.speedFromChart(sliderValue.toDouble(), units)
    fun label(kph: Double): String = UnitFormatter.speed(kph, units, locale)
}

private const val OpenMeteoUrl = "https://open-meteo.com/"
