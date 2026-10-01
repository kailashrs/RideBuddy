package com.spaceboy.ridebuddy.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performScrollToNode
import com.spaceboy.ridebuddy.MainUiState
import com.spaceboy.ridebuddy.core.navigation.NavigationKeyUiState
import com.spaceboy.ridebuddy.RideDetailContent
import com.spaceboy.ridebuddy.RideDetailUiData
import com.spaceboy.ridebuddy.TopLevelDestination
import com.spaceboy.ridebuddy.core.navigation.GuidanceState
import com.spaceboy.ridebuddy.data.DistanceUnits
import com.spaceboy.ridebuddy.data.ThemeMode
import com.spaceboy.ridebuddy.data.telemetryChartData
import com.spaceboy.ridebuddy.domain.BikeConnectionState
import com.spaceboy.ridebuddy.ui.screens.LiveCardFixture
import com.spaceboy.ridebuddy.ui.theme.Rs457Theme
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders every screen and its notable states, in both themes, into
 * `app/build/outputs/review/`. A tall window so a whole scrolling screen is one picture; this
 * is for looking at the app as a whole, not for asserting on it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rIN-w360dp-h2000dp-xhdpi")
class UiReviewRenderTest {
    @get:Rule val compose = createComposeRule()

    private var dark by mutableStateOf(false)
    private var scene by mutableStateOf<@Composable () -> Unit>({})

    private fun start() = compose.setContent {
        Rs457Theme(themeMode = if (dark) ThemeMode.Dark else ThemeMode.Light, dynamicColor = false) {
            Surface(Modifier.fillMaxSize()) { scene() }
        }
    }

    private fun shell(ui: MainUiState, state: (MainUiState) -> MainScreenState = { AppUiFixture.state(it) }): @Composable () -> Unit = {
        val actions = AppUiFixture.actions()
        MainScreen(ui, actions) { modifier -> MainScreenContent(modifier, state(ui), actions) }
    }

    private fun both(name: String, content: @Composable () -> Unit, capture: () -> Unit = { shot(name) }) {
        for (night in listOf(false, true)) {
            compose.runOnIdle { dark = night; scene = content }
            compose.waitForIdle()
            capture()
            // captured by the lambda under the theme's name
            renameLast(name, night)
        }
    }

    private var last: File? = null

    private fun shot(name: String, dialog: Boolean = false, sheet: Boolean = false) {
        val node = when {
            sheet -> compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.PaneTitle))
            dialog -> compose.onNode(isDialog())
            else -> compose.onRoot()
        }
        val bitmap = node.captureToImage().asAndroidBitmap()
        val file = File(File("build/outputs/review").apply { mkdirs() }, "$name.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        last = file
    }

    private fun renameLast(name: String, night: Boolean) {
        val file = last ?: return
        file.renameTo(File(file.parentFile, "$name-${if (night) "dark" else "light"}.png"))
        last = null
    }

    private val configured = MainUiState(navigationKey = NavigationKeyUiState(isConfigured = true, maskedKey = "•••• 1234"))

    @Test fun topLevelDestinations() {
        start()
        TopLevelDestination.entries.forEach { destination ->
            both("top-${destination.name.lowercase()}", shell(configured.copy(selectedDestination = destination)))
        }
    }

    @Test fun liveStates() {
        start()
        val base = configured
        both("live-unpaired", shell(base) {
            AppUiFixture.state(it).copy(
                connectionState = BikeConnectionState.Disconnected,
                bikeAssociation = AppUiFixture.state(it).bikeAssociation.copy(bike = null),
                rides = emptyList(),
            )
        })
        both("live-disconnected", shell(base) { AppUiFixture.state(it).copy(connectionState = BikeConnectionState.Disconnected) })
        both("live-connecting", shell(base) { AppUiFixture.state(it).copy(connectionState = BikeConnectionState.Connecting("Aprilia RS 457")) })
        both("live-failed", shell(base) {
            AppUiFixture.state(it).copy(connectionState = BikeConnectionState.Failed("The bike did not answer. Turn the ignition on and try again."))
        })
        both("live-navigating", shell(base) {
            AppUiFixture.state(it).copy(guidance = GuidanceState(active = true, instruction = "Turn left onto Anna Salai",
                roadName = "Anna Salai", distanceToManeuverMetres = 350, distanceToDestinationMetres = 12_400, timeToDestinationSeconds = 1_500))
        })
        both("live-shared-error", shell(base.copy(sharedDestination = "https://maps.app.goo.gl/abc", sharedDestinationError = "That link doesn't contain a destination.")))
    }

    @Test fun liveSheet() {
        start()
        both("live-sheet", { LiveCardFixture.LiveScreenUnderTest(ride = LiveCardFixture.recording(), samples = LiveCardFixture.samples()) }) {
            compose.onAllNodesWithText("Details").onFirst().performClick()
            compose.waitForIdle()
            shot("live-sheet", sheet = true)
            compose.runOnIdle { scene = {} }
            compose.waitForIdle()
        }
    }

    @Test fun overlaysAndEmpty() {
        start()
        both("navigation-settings", shell(configured.copy(isNavigationSettingsOpen = true)))
        both("navigation-settings-empty", shell(MainUiState(isNavigationSettingsOpen = true)))
        both("diagnostics", shell(configured.copy(isDiagnosticsOpen = true)))
        both("history-empty", shell(configured.copy(selectedDestination = TopLevelDestination.History)) {
            AppUiFixture.state(it).copy(rides = emptyList())
        })
        both("insights-empty", shell(configured.copy(selectedDestination = TopLevelDestination.Insights)) {
            AppUiFixture.state(it).copy(insights = com.spaceboy.ridebuddy.data.RideInsights())
        })
    }

    @Test fun onboarding() {
        start()
        val onboarding: @Composable () -> Unit = {
            OnboardingScreen(
                connectionState = BikeConnectionState.Disconnected, bikeAssociated = false,
                nearbyDeviceAccessGranted = true, preciseLocationGranted = false,
                notificationAccessEnabled = false, appNotificationPermissionGranted = false,
                telemetryReceiving = false, authenticated = false, navigationConfigured = false,
                onRequestNearbyDeviceAccess = {}, onRequestPreciseLocation = {}, onAssociateBike = {},
                onOpenNotificationAccess = {}, onRequestAppNotificationPermission = {},
                onSetUpNavigation = {}, onComplete = {},
            )
        }
        for (night in listOf(false, true)) {
            compose.runOnIdle { dark = night; scene = {} }
            compose.waitForIdle()
            compose.runOnIdle { scene = onboarding }
            compose.waitForIdle()
            for (page in 0 until 4) {
                shot("onboarding-$page-${if (night) "dark" else "light"}")
                val next = compose.onAllNodesWithText("Continue").fetchSemanticsNodes()
                if (next.isEmpty()) break
                compose.onAllNodesWithText("Continue").onFirst().performClick()
                compose.waitForIdle()
            }
        }
    }

    @Test fun rideDetail() {
        start()
        val samples = LiveCardFixture.samples()
        val data = RideDetailUiData(
            AppUiFixture.ride.copy(endLatitude = 13.05, endLongitude = 80.28, zeroToSixtyMillis = 4_300),
            hasSamples = true, hasLocations = true, routePoints = List(20) { 13.0 + it * 0.002 to 80.25 + it * 0.001 },
            speedValues = telemetryChartData(samples, 600) { it.speedKph },
            rpmValues = telemetryChartData(samples, 600) { it.rpm.toDouble() },
            throttleValues = telemetryChartData(samples, 600) { it.throttlePercent.toDouble() },
            events = emptyList(),
        )
        both("ride-detail", {
            RideDetailContent(data, DistanceUnits.Metric, onOpenParking = {})
        })
    }

    @Test fun settingsDialogs() {
        start()
        val settings = shell(configured.copy(selectedDestination = TopLevelDestination.Settings))
        listOf("App notifications" to "dialog-supported-apps", "Theme" to "dialog-theme", "Keep detailed ride data" to "dialog-retention",
            "About" to "dialog-about", "Test the bike's display" to "dialog-display-test",
            "Captured packets" to "dialog-captured-packets").forEach { (row, name) ->
            both(name, settings) {
                compose.onNode(androidx.compose.ui.test.hasScrollToNodeAction())
                    .performScrollToNode(androidx.compose.ui.test.hasText(row))
                compose.onAllNodesWithText(row).onFirst().performClick()
                compose.waitForIdle()
                shot(name, dialog = true)
                compose.runOnIdle { scene = {} }
                compose.waitForIdle()
            }
        }
        // The end of the list, where the developer tools section sits.
        both("settings-bottom", settings) {
            compose.onNode(androidx.compose.ui.test.hasScrollToNodeAction())
                .performScrollToNode(androidx.compose.ui.test.hasText("Captured packets"))
            compose.waitForIdle()
            shot("settings-bottom")
        }
    }
}

/** The rider's phone as it is: Vivo V2454A, 1440x3168 at 640 dpi, Android 16, font scale 1.0. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rIN-w360dp-h792dp-640dpi")
class UiDeviceRenderTest {
    @get:Rule val compose = createComposeRule()

    @Test fun topLevelDestinationsAtDeviceSize() {
        var ui by mutableStateOf(MainUiState(navigationKey = NavigationKeyUiState(isConfigured = true, maskedKey = "•••• 1234")))
        var night by mutableStateOf(false)
        compose.setContent {
            Rs457Theme(themeMode = if (night) ThemeMode.Dark else ThemeMode.Light, dynamicColor = false) {
                val actions = AppUiFixture.actions { ui = ui.copy(selectedDestination = it) }
                MainScreen(ui, actions) { modifier -> MainScreenContent(modifier, AppUiFixture.state(ui), actions) }
            }
        }
        for (dark in listOf(false, true)) {
            compose.runOnIdle { night = dark }
            TopLevelDestination.entries.forEach { destination ->
                compose.runOnIdle { ui = ui.copy(selectedDestination = destination) }
                compose.waitForIdle()
                val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
                val file = File(File("build/outputs/review").apply { mkdirs() }, "device-${destination.name.lowercase()}-${if (dark) "dark" else "light"}.png")
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
    }
}
