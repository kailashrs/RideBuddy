package com.spaceboy.ridebuddy.data

import android.content.Context
import android.icu.util.LocaleData
import android.icu.util.ULocale
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.dataStoreFile
import androidx.datastore.migrations.SharedPreferencesMigration
import androidx.datastore.migrations.SharedPreferencesView
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class DistanceUnits {
    Metric,
    Imperial;

    companion object {
        /** The locale's road units, from ICU. A saved preference always takes precedence. */
        fun defaultFor(locale: Locale): DistanceUnits =
            if (measurementSystem(locale) == LocaleData.MeasurementSystem.SI) Metric else Imperial

        /** Whether the locale's gallon is the US one; the UK's is about 20% larger. */
        fun usesUsGallons(locale: Locale): Boolean = measurementSystem(locale) == LocaleData.MeasurementSystem.US

        private fun measurementSystem(locale: Locale) = LocaleData.getMeasurementSystem(ULocale.forLocale(locale))
    }
}
enum class ThemeMode { System, Light, Dark }

/**
 * How much text the cluster's navigation rows carry. [Compact] drops the destination lines
 * and keeps only the instruction banner, which is the half a rider glances at.
 */
enum class TftTextMode { Full, Compact }

/**
 * How long a ride's telemetry samples are kept.
 *
 * Only the samples expire. The ride, its summary figures, its records and its route preview
 * are kept for good, so history, insights and weekly totals reach back as far as they ever
 * did however short a window this is — what ages out is a ride's detail charts and its
 * per-sample exports.
 *
 * The series is what makes history grow: a summary is under a kilobyte, while samples run to
 * roughly half a megabyte for every hour ridden. A window is what turns unbounded growth
 * into a ceiling, which is why the default is a generous one rather than [Forever].
 */
enum class SampleRetention(val days: Int?, val label: String) {
    ThirtyDays(30, "30 days"),
    NinetyDays(90, "90 days"),
    SixMonths(182, "6 months"),
    OneYear(365, "1 year"),
    Forever(null, "Keep everything"),
}

/**
 * Every user preference, as one immutable value.
 *
 * Replaced wholesale on each change rather than mutated, so Compose can skip on it. The
 * defaults here are the app's actual defaults — note that everything writing to the
 * vehicle ([callerDisplay], [tftCallControls], [tftNavigationOutputEnabled]) and everything
 * touching diagnostics capture is off until the rider turns it on.
 */
@Serializable
data class AppSettings(
    /** The store's default value replaces this with the locale's units; see [AppSettingsRepository]. */
    val distanceUnits: DistanceUnits = DistanceUnits.Metric,
    val voiceGuidance: Boolean = true,
    val avoidTolls: Boolean = false,
    val avoidHighways: Boolean = false,
    val avoidFerries: Boolean = false,
    val autoStartSharedDestinations: Boolean = false,
    val callerDisplay: Boolean = false,
    val tftCallControls: Boolean = false,
    val tftNavigationOutputEnabled: Boolean = false,
    val bleCaptureEnabled: Boolean = false,
    val persistConnectionDiagnostics: Boolean = false,
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
    val sampleRetention: SampleRetention = SampleRetention.OneYear,
    val themeMode: ThemeMode = ThemeMode.System,
    val dynamicColor: Boolean = true,
    val highContrast: Boolean = false,
    /**
     * Apps whose icon the rider has switched off.
     *
     * Stored as the exclusions rather than the inclusions so an app that is resolved at
     * runtime — the default SMS app — is on by default like every listed one, instead of
     * being invisible until something remembers to add it.
     */
    val disabledNotificationPackages: Set<String> = emptySet(),
)

/**
 * Reads and writes [AppSettings] through a DataStore.
 *
 * Settings are read on hot paths like telemetry handling, so the current value is held in a
 * [StateFlow]; the first value is loaded before construction returns. Writes run one at a time
 * in call order.
 */
class AppSettingsRepository(
    private val store: DataStore<AppSettings>,
    private val scope: CoroutineScope,
) {
    val settings: StateFlow<AppSettings> =
        store.data.stateIn(scope, SharingStarted.Eagerly, runBlocking { store.data.first() })

    private val writeDispatcher = Dispatchers.IO.limitedParallelism(1)

    fun update(transform: (AppSettings) -> AppSettings) {
        scope.launch(writeDispatcher) { store.updateData(transform) }
    }

    companion object {
        const val FileName = "settings.json"

        fun create(context: Context, scope: CoroutineScope): AppSettingsRepository = AppSettingsRepository(
            DataStoreFactory.create(
                serializer = JsonSerializer(
                    AppSettings.serializer(),
                    AppSettings(distanceUnits = DistanceUnits.defaultFor(Locale.getDefault())),
                ),
                migrations = listOf(legacySettingsMigration(context)),
                scope = CoroutineScope(scope.coroutineContext + Dispatchers.IO),
                produceFile = { context.dataStoreFile(FileName) },
            ),
            scope,
        )
    }
}

/**
 * Reads the pre-1.1 `app_settings` preferences once. Only keys the previous build wrote are
 * mapped; anything absent keeps its default.
 */
internal fun legacySettingsMigration(context: Context) =
    SharedPreferencesMigration<AppSettings>(context, "app_settings") { old, defaults ->
        old.toAppSettings(defaults)
    }

internal fun SharedPreferencesView.toAppSettings(defaults: AppSettings): AppSettings = AppSettings(
    distanceUnits = enum("units", defaults.distanceUnits),
    voiceGuidance = getBoolean("voice_guidance", defaults.voiceGuidance),
    avoidTolls = getBoolean("avoid_tolls", defaults.avoidTolls),
    avoidHighways = getBoolean("avoid_highways", defaults.avoidHighways),
    avoidFerries = getBoolean("avoid_ferries", defaults.avoidFerries),
    autoStartSharedDestinations = getBoolean("auto_start_shared_v2", defaults.autoStartSharedDestinations),
    callerDisplay = getBoolean("caller_display", defaults.callerDisplay),
    tftCallControls = getBoolean("tft_call_controls", defaults.tftCallControls),
    tftNavigationOutputEnabled = getBoolean("tft_navigation_output", defaults.tftNavigationOutputEnabled),
    bleCaptureEnabled = getBoolean("ble_capture_enabled", defaults.bleCaptureEnabled),
    persistConnectionDiagnostics = getBoolean("persist_connection_diagnostics", defaults.persistConnectionDiagnostics),
    onboardingComplete = getBoolean("onboarding_complete", defaults.onboardingComplete),
    rideStartSpeedKph = getFloat("ride_start_speed", defaults.rideStartSpeedKph.toFloat()).toDouble(),
    rideStopSpeedKph = getFloat("ride_stop_speed", defaults.rideStopSpeedKph.toFloat()).toDouble(),
    rideStopDelaySeconds = getInt("ride_stop_delay", defaults.rideStopDelaySeconds),
    overspeedAlerts = getBoolean("overspeed_alerts", defaults.overspeedAlerts),
    overspeedThresholdKph = getInt("overspeed_threshold", defaults.overspeedThresholdKph),
    rpmAlerts = getBoolean("rpm_alerts", defaults.rpmAlerts),
    rpmThreshold = getInt("rpm_threshold", defaults.rpmThreshold),
    accelerationAlerts = getBoolean("acceleration_alerts", defaults.accelerationAlerts),
    brakingAlerts = getBoolean("braking_alerts", defaults.brakingAlerts),
    weatherAlerts = getBoolean("weather_alerts", defaults.weatherAlerts),
    hazardAlerts = getBoolean("hazard_alerts", defaults.hazardAlerts),
    tftTextMode = enum("tft_text_mode", defaults.tftTextMode),
    sampleRetention = enum("sample_retention", defaults.sampleRetention),
    themeMode = enum("theme_mode", defaults.themeMode),
    dynamicColor = getBoolean("dynamic_color", defaults.dynamicColor),
    highContrast = getBoolean("high_contrast", defaults.highContrast),
    disabledNotificationPackages = getStringSet("notification_packages_disabled") ?: defaults.disabledNotificationPackages,
)

private inline fun <reified T : Enum<T>> SharedPreferencesView.enum(key: String, fallback: T): T =
    getString(key)?.let { name -> enumValues<T>().firstOrNull { it.name == name } } ?: fallback

/** A DataStore serializer for any `@Serializable` value, stored as JSON. */
internal class JsonSerializer<T>(
    private val serializer: KSerializer<T>,
    override val defaultValue: T,
) : Serializer<T> {
    override suspend fun readFrom(input: InputStream): T = try {
        StoredJson.decodeFromString(serializer, input.readBytes().decodeToString())
    } catch (error: SerializationException) {
        throw CorruptionException("Unreadable ${serializer.descriptor.serialName}", error)
    }

    override suspend fun writeTo(t: T, output: OutputStream) {
        output.write(StoredJson.encodeToString(serializer, t).encodeToByteArray())
    }
}

/**
 * Unknown keys are ignored and absent ones take their defaults, so a field can be added freely.
 * Defaults are still written, so a locale-derived default the rider accepted stays put.
 */
private val StoredJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
    coerceInputValues = true
}
