package com.spaceboy.ridebuddy.data

import android.content.Context
import androidx.core.content.edit
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class DistanceUnits {
    Metric,
    Imperial;

    companion object {
        /** Road distance and speed defaults. A saved preference always takes precedence. */
        fun defaultFor(locale: Locale): DistanceUnits = when (locale.country.uppercase(Locale.ROOT)) {
            "US", "GB", "LR", "MM" -> Imperial
            else -> Metric
        }
    }
}
enum class ThemeMode { System, Light, Dark }
enum class TftTextMode { Full, Compact }

data class AppSettings(
    val distanceUnits: DistanceUnits = DistanceUnits.defaultFor(Locale.getDefault()),
    val voiceGuidance: Boolean = true,
    val avoidTolls: Boolean = false,
    val avoidHighways: Boolean = false,
    val avoidFerries: Boolean = false,
    val autoStartSharedDestinations: Boolean = false,
    val messageAlerts: Boolean = true,
    val socialAlerts: Boolean = true,
    val emailAlerts: Boolean = true,
    val legacyCallControls: Boolean = false,
    val callerDisplay: Boolean = false,
    val tftCallControls: Boolean = false,
    val tftNavigationOutputEnabled: Boolean = false,
    val bleCaptureEnabled: Boolean = false,
    val onboardingComplete: Boolean = false,
    val rideStartSpeedKph: Double = 3.0,
    val rideStopSpeedKph: Double = 1.0,
    val rideStopDelaySeconds: Int = 120,
    val overspeedAlerts: Boolean = false,
    val overspeedThresholdKph: Int = 100,
    val rpmAlerts: Boolean = false,
    val rpmThreshold: Int = 8_000,
    val accelerationAlerts: Boolean = false,
    val brakingAlerts: Boolean = false,
    val weatherAlerts: Boolean = false,
    val hazardAlerts: Boolean = false,
    val tftTextMode: TftTextMode = TftTextMode.Full,
    val themeMode: ThemeMode = ThemeMode.System,
    val dynamicColor: Boolean = true,
    val highContrast: Boolean = false,
    val enabledNotificationPackages: Set<String> = DefaultNotificationPackages,
)

class AppSettingsRepository(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(Name, Context.MODE_PRIVATE)
    private val mutableSettings = MutableStateFlow(read())
    val settings: StateFlow<AppSettings> = mutableSettings.asStateFlow()

    @Synchronized
    fun update(transform: (AppSettings) -> AppSettings) {
        val updated = transform(mutableSettings.value)
        preferences.edit {
            putString(KeyUnits, updated.distanceUnits.name)
            putBoolean(KeyVoice, updated.voiceGuidance)
            putBoolean(KeyTolls, updated.avoidTolls)
            putBoolean(KeyHighways, updated.avoidHighways)
            putBoolean(KeyFerries, updated.avoidFerries)
            putBoolean(KeyAutoStartV2, updated.autoStartSharedDestinations)
            putBoolean(KeyMessages, updated.messageAlerts)
            putBoolean(KeySocial, updated.socialAlerts)
            putBoolean(KeyEmail, updated.emailAlerts)
            putBoolean(KeyLegacyCalls, updated.legacyCallControls)
            putBoolean(KeyCallerDisplay, updated.callerDisplay)
            putBoolean(KeyTftCallControls, updated.tftCallControls)
            putBoolean(KeyTftNavigationOutput, updated.tftNavigationOutputEnabled)
            putBoolean(KeyBleCaptureEnabled, updated.bleCaptureEnabled)
            putBoolean(KeyOnboarding, updated.onboardingComplete)
            putFloat(KeyRideStart, updated.rideStartSpeedKph.toFloat())
            putFloat(KeyRideStop, updated.rideStopSpeedKph.toFloat())
            putInt(KeyRideStopDelay, updated.rideStopDelaySeconds)
            putBoolean(KeyOverspeedAlerts, updated.overspeedAlerts)
            putInt(KeyOverspeedThreshold, updated.overspeedThresholdKph)
            putBoolean(KeyRpmAlerts, updated.rpmAlerts)
            putInt(KeyRpmThreshold, updated.rpmThreshold)
            putBoolean(KeyAccelerationAlerts, updated.accelerationAlerts)
            putBoolean(KeyBrakingAlerts, updated.brakingAlerts)
            putBoolean(KeyWeatherAlerts, updated.weatherAlerts)
            putBoolean(KeyHazardAlerts, updated.hazardAlerts)
            putString(KeyTftText, updated.tftTextMode.name)
            putString(KeyTheme, updated.themeMode.name)
            putBoolean(KeyDynamicColor, updated.dynamicColor)
            putBoolean(KeyHighContrast, updated.highContrast)
            putStringSet(KeyNotificationPackages, updated.enabledNotificationPackages)
        }
        mutableSettings.value = updated
    }

    private fun read(): AppSettings {
        return AppSettings(
            distanceUnits = preferences.getString(KeyUnits, null)
                ?.let { runCatching { DistanceUnits.valueOf(it) }.getOrNull() }
                ?: DistanceUnits.defaultFor(Locale.getDefault()),
            voiceGuidance = preferences.getBoolean(KeyVoice, true),
            avoidTolls = preferences.getBoolean(KeyTolls, false),
            avoidHighways = preferences.getBoolean(KeyHighways, false),
            avoidFerries = preferences.getBoolean(KeyFerries, false),
            // V2 is intentionally opt-in. The previous key defaulted to automatic launch, so it
            // cannot distinguish an explicit user choice from the legacy implicit default.
            autoStartSharedDestinations = preferences.getBoolean(KeyAutoStartV2, false),
            messageAlerts = preferences.getBoolean(KeyMessages, true),
            socialAlerts = preferences.getBoolean(KeySocial, true),
            emailAlerts = preferences.getBoolean(KeyEmail, true),
            legacyCallControls = preferences.getBoolean(KeyLegacyCalls, false),
            callerDisplay = preferences.getBoolean(KeyCallerDisplay, false),
            tftCallControls = preferences.getBoolean(KeyTftCallControls, false),
            tftNavigationOutputEnabled = preferences.getBoolean(KeyTftNavigationOutput, false),
            bleCaptureEnabled = preferences.getBoolean(KeyBleCaptureEnabled, false),
            onboardingComplete = preferences.getBoolean(KeyOnboarding, false),
            rideStartSpeedKph = preferences.getFloat(KeyRideStart, 3f).toDouble(),
            rideStopSpeedKph = preferences.getFloat(KeyRideStop, 1f).toDouble(),
            rideStopDelaySeconds = preferences.getInt(KeyRideStopDelay, 120),
            overspeedAlerts = preferences.getBoolean(KeyOverspeedAlerts, false),
            overspeedThresholdKph = preferences.getInt(KeyOverspeedThreshold, 100),
            rpmAlerts = preferences.getBoolean(KeyRpmAlerts, false),
            rpmThreshold = preferences.getInt(KeyRpmThreshold, 8_000),
            accelerationAlerts = preferences.getBoolean(KeyAccelerationAlerts, false),
            brakingAlerts = preferences.getBoolean(KeyBrakingAlerts, false),
            weatherAlerts = preferences.getBoolean(KeyWeatherAlerts, false),
            hazardAlerts = preferences.getBoolean(KeyHazardAlerts, false),
            tftTextMode = preferences.enum(KeyTftText, TftTextMode.Full),
            themeMode = preferences.enum(KeyTheme, ThemeMode.System),
            dynamicColor = preferences.getBoolean(KeyDynamicColor, true),
            highContrast = preferences.getBoolean(KeyHighContrast, false),
            enabledNotificationPackages = preferences.getStringSet(KeyNotificationPackages, DefaultNotificationPackages)?.toSet()
                ?: DefaultNotificationPackages,
        )
    }

    private companion object {
        const val Name = "app_settings"
        const val KeyUnits = "units"
        const val KeyVoice = "voice_guidance"
        const val KeyTolls = "avoid_tolls"
        const val KeyHighways = "avoid_highways"
        const val KeyFerries = "avoid_ferries"
        const val KeyAutoStartV2 = "auto_start_shared_v2"
        const val KeyMessages = "message_alerts"
        const val KeySocial = "social_alerts"
        const val KeyEmail = "email_alerts"
        const val KeyLegacyCalls = "legacy_call_controls"
        const val KeyCallerDisplay = "caller_display"
        const val KeyTftCallControls = "tft_call_controls"
        const val KeyTftNavigationOutput = "tft_navigation_output"
        const val KeyBleCaptureEnabled = "ble_capture_enabled"
        const val KeyOnboarding = "onboarding_complete"
        const val KeyRideStart = "ride_start_speed"
        const val KeyRideStop = "ride_stop_speed"
        const val KeyRideStopDelay = "ride_stop_delay"
        const val KeyOverspeedAlerts = "overspeed_alerts"
        const val KeyOverspeedThreshold = "overspeed_threshold"
        const val KeyRpmAlerts = "rpm_alerts"
        const val KeyRpmThreshold = "rpm_threshold"
        const val KeyAccelerationAlerts = "acceleration_alerts"
        const val KeyBrakingAlerts = "braking_alerts"
        const val KeyWeatherAlerts = "weather_alerts"
        const val KeyHazardAlerts = "hazard_alerts"
        const val KeyTftText = "tft_text_mode"
        const val KeyTheme = "theme_mode"
        const val KeyDynamicColor = "dynamic_color"
        const val KeyHighContrast = "high_contrast"
        const val KeyNotificationPackages = "notification_packages"
    }
}

private inline fun <reified T : Enum<T>> android.content.SharedPreferences.enum(key: String, fallback: T): T =
    getString(key, null)?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: fallback
