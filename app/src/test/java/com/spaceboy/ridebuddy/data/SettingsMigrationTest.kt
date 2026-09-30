package com.spaceboy.ridebuddy.data

import android.content.Context
import androidx.core.content.edit
import androidx.datastore.core.DataStoreFactory
import android.net.MacAddress
import com.spaceboy.ridebuddy.ble.LinkState
import com.spaceboy.ridebuddy.ble.legacyLinkStateMigrations
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SettingsMigrationTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun `current app_settings keys are carried into the settings store`() = runBlocking {
        context.getSharedPreferences("app_settings", Context.MODE_PRIVATE).edit(commit = true) {
            putString("units", "Imperial")
            putBoolean("tft_navigation_output", true)
            putBoolean("auto_start_shared_v2", true)
            putFloat("ride_start_speed", 5f)
            putInt("rpm_threshold", 9_500)
            putString("sample_retention", "NinetyDays")
            putString("theme_mode", "NotAThemeAnyMore")
            putStringSet("notification_packages_disabled", setOf("com.whatsapp"))
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = DataStoreFactory.create(
            serializer = JsonSerializer(AppSettings.serializer(), AppSettings()),
            migrations = listOf(legacySettingsMigration(context)),
            scope = scope,
            produceFile = { File(context.filesDir, "settings-test.json") },
        )

        val settings = store.data.first()

        assertEquals(DistanceUnits.Imperial, settings.distanceUnits)
        assertTrue(settings.tftNavigationOutputEnabled)
        assertTrue(settings.autoStartSharedDestinations)
        assertEquals(5.0, settings.rideStartSpeedKph, 1e-6)
        assertEquals(9_500, settings.rpmThreshold)
        assertEquals(SampleRetention.NinetyDays, settings.sampleRetention)
        assertEquals(ThemeMode.System, settings.themeMode)
        assertEquals(setOf("com.whatsapp"), settings.disabledNotificationPackages)
        assertFalse(context.getSharedPreferences("app_settings", Context.MODE_PRIVATE).contains("units"))
        scope.cancel()
    }

    @Test
    fun `the older enabled-app key becomes the disabled set`() = runBlocking {
        context.getSharedPreferences("app_settings", Context.MODE_PRIVATE).edit(commit = true) {
            putStringSet("notification_packages_v2", SupportedNotificationAppsByPackage.keys - "com.whatsapp")
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = DataStoreFactory.create(
            serializer = JsonSerializer(AppSettings.serializer(), AppSettings()),
            migrations = listOf(legacySettingsMigration(context)),
            scope = scope,
            produceFile = { File(context.filesDir, "settings-legacy-test.json") },
        )

        assertEquals(setOf("com.whatsapp"), store.data.first().disabledNotificationPackages)
        scope.cancel()
    }

    @Test
    fun `protection acceptance, identity and connection demand are carried into the link store`() = runBlocking {
        val address = requireNotNull(MacAddress.fromString("CC:B3:1E:C1:E1:B7"))
        context.getSharedPreferences("ble_protection_trust", Context.MODE_PRIVATE).edit(commit = true) {
            putLong("trusted_address", 0xCCB31EC1E1B7L)
        }
        context.getSharedPreferences("bike_identity", Context.MODE_PRIVATE).edit(commit = true) {
            putLong("address", 0xCCB31EC1E1B7L)
            putString("vin", "ZD4TESTVIN1234567")
            putLong("last_connected", 1234L)
        }
        context.getSharedPreferences("bike_connection_demand", Context.MODE_PRIVATE).edit(commit = true) {
            putBoolean("automatic_connection_suppressed", true)
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        val store = DataStoreFactory.create(
            serializer = JsonSerializer(LinkState.serializer(), LinkState()),
            migrations = legacyLinkStateMigrations(context),
            scope = scope,
            produceFile = { File(context.filesDir, "link-state-test.json") },
        )

        val state = store.data.first()

        assertEquals(address.toString(), state.protectionAcceptedAddress)
        assertEquals(address.toString(), state.identity?.address)
        assertEquals("ZD4TESTVIN1234567", state.identity?.vin)
        assertEquals(1234L, state.identity?.lastConnectedAtMillis)
        assertTrue(state.automaticConnectionSuppressed)
        assertFalse(state.awaitingBleAppearance)
        scope.cancel()
    }
}
