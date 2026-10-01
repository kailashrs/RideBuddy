package com.spaceboy.ridebuddy.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.os.PowerManager
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.AltRoute
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.ColorLens
import androidx.compose.material.icons.outlined.ContactPage
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Directions
import androidx.compose.material.icons.outlined.DirectionsBoat
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Memory
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.spaceboy.ridebuddy.data.TftTextMode
import com.spaceboy.ridebuddy.data.ThemeMode
import com.spaceboy.ridebuddy.data.UnitFormatter
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

    // Re-read on resume: this is changed in system settings, which does not notify.
    var unrestrictedBattery by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        unrestrictedBattery = context.getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(context.packageName) == true
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
            text = "This permanently deletes ${state.rides.size} ${if (state.rides.size == 1) "ride" else "rides"} with their routes and stats. Export anything you want to keep first.",
            confirm = "Delete",
            onDismiss = { dialog = null },
            onConfirm = actions.onClearRideHistory,
        )
        SettingsDialog.ClearDestinations -> ConfirmDialog(
            title = "Clear recent destinations?",
            text = "Places you've ridden to and their trip counts are forgotten. Saved places stay.",
            confirm = "Clear",
            onDismiss = { dialog = null },
            onConfirm = actions.onClearRecentDestinations,
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
        SettingsDialog.DisplayTest -> AlertDialog(
            onDismissRequest = { dialog = null },
            icon = { Icon(Icons.Outlined.Tv, contentDescription = null) },
            title = { Text("Test the bike's display?") },
            text = {
                Text(
                    "Only while parked, with no call in progress. In turn, the display shows:\n\n" +
                        "1. A turn, distances and a speed limit\n" +
                        "2. A test caller ringing, answered, ended and outgoing\n\n" +
                        "You'll be asked whether each part appeared.",
                )
            },
            confirmButton = { TextButton(onClick = { dialog = null; actions.onRunStationaryTest() }) { Text("Run test") } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
        )
        SettingsDialog.Capture -> BleCaptureDialog(state.bleCapture, actions.onExportBleCapture, actions.onClearBleCapture) {
            dialog = null
        }
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
                        else -> "Choose your bike from the list"
                    },
                    icon = Icons.Outlined.Bluetooth,
                    enabled = !state.bikeAssociation.associationInProgress,
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
            SettingsRow("Delete all rides", "${state.rides.size} ${if (state.rides.size == 1) "ride" else "rides"} on this phone",
                icon = Icons.Outlined.DeleteOutline, enabled = state.rides.isNotEmpty(),
                onClick = { dialog = SettingsDialog.ClearHistory })
            SettingsRow("Clear recent destinations", "Saved places are kept",
                icon = Icons.Outlined.History,
                enabled = state.destinations.any { !it.isSaved || it.tripCount > 0 },
                onClick = { dialog = SettingsDialog.ClearDestinations })
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
            SettingsRow("Battery use", if (unrestrictedBattery) "Unrestricted" else "Optimized — may delay reconnecting",
                icon = Icons.Outlined.BatteryAlert, onClick = actions.onOpenAppPermissions)
            SettingsRow("App permissions", null, icon = Icons.Outlined.Security, onClick = actions.onOpenAppPermissions)
            SettingsRow("Run setup again", null, icon = Icons.Outlined.RestartAlt, onClick = actions.onResetOnboarding)
            SettingsRow("About", "Version ${BuildConfig.VERSION_NAME}", icon = Icons.Outlined.Info,
                onClick = { dialog = SettingsDialog.About })
        }
        // For troubleshooting while parked; nothing here is needed to ride.
        section("Developer tools") {
            SettingsRow("Connection details", "Live link state, recent events and a shareable report",
                icon = Icons.Outlined.Bluetooth, onClick = actions.onOpenDiagnostics)
            SettingsRow("Test the bike's display", "Sample directions and a test call",
                icon = Icons.Outlined.Tv, onClick = { dialog = SettingsDialog.DisplayTest })
            SettingsSwitchRow("Capture Bluetooth traffic", "Raw packets in memory; may include names and message text",
                state.settings.bleCaptureEnabled, icon = Icons.Outlined.BugReport,
                onCheckedChange = settingsActions.onBleCaptureEnabledChanged)
            SettingsRow(
                "Captured packets",
                when {
                    state.bleCapture.entries.isEmpty() -> "None yet"
                    state.bleCapture.droppedEntries > 0 ->
                        "${state.bleCapture.entries.size} kept, ${state.bleCapture.droppedEntries} older dropped"
                    else -> "${state.bleCapture.entries.size} kept"
                },
                icon = Icons.AutoMirrored.Outlined.ReceiptLong,
                onClick = { dialog = SettingsDialog.Capture },
            )
        }
    }
}

private enum class SettingsDialog { ForgetBike, ClearHistory, ClearDestinations, About, DisplayTest, Capture }

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
