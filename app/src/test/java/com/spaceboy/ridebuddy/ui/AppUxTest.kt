package com.spaceboy.ridebuddy.ui

import android.graphics.Bitmap
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.spaceboy.ridebuddy.*
import com.spaceboy.ridebuddy.data.*
import com.spaceboy.ridebuddy.domain.*
import com.spaceboy.ridebuddy.ui.screens.*
import com.spaceboy.ridebuddy.ui.theme.Rs457Theme
import org.junit.Assert.*
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rGB-w411dp-h891dp-xxhdpi")
class AppUxTest {
    @get:Rule val compose = createComposeRule()
    @After fun resetFontScale() { RuntimeEnvironment.setFontScale(1f) }

    @Test fun allMainDestinationsRenderInLightAndDark() {
        val ui = mutableStateOf(MainUiState(navigationKey = NavigationKeyUiState(isConfigured = true, maskedKey = "•••• 1234")))
        val dark = mutableStateOf(false)
        compose.setContent {
            Rs457Theme(themeMode = if (dark.value) ThemeMode.Dark else ThemeMode.Light, dynamicColor = false) {
                val state = AppUiFixture.state(ui.value)
                val actions = AppUiFixture.actions { ui.value = ui.value.copy(selectedDestination = it) }
                MainScreen(ui.value, actions) { modifier -> MainScreenContent(modifier, state, actions) }
            }
        }
        for (night in listOf(false, true)) {
            compose.runOnIdle { dark.value = night }
            for (destination in TopLevelDestination.entries) {
                compose.onNodeWithTag("top-level-${destination.name}").performClick()
                compose.waitForIdle()
                if (destination == TopLevelDestination.History) {
                    compose.waitUntil(5_000) { compose.onAllNodesWithText("1 ride ·", substring = true).fetchSemanticsNodes().isNotEmpty() }
                }
                capture("app-${destination.name.lowercase()}-${if (night) "dark" else "light"}")
            }
        }
    }

    @Test
    @Config(qualifiers = "en-rGB-w320dp-h740dp-xxhdpi")
    fun insightsLargeTextHasReadablePeriodChoicesAndMetrics() {
        RuntimeEnvironment.setFontScale(2f)
        val period = mutableStateOf(InsightPeriod.ThirtyDays)
        compose.setContent {
            Rs457Theme(themeMode = ThemeMode.Light, dynamicColor = false) {
                Surface(Modifier.fillMaxSize()) {
                    InsightsScreen(insights = AppUiFixture.state(MainUiState()).insights, units = DistanceUnits.Metric,
                        selectedPeriod = period.value, onPeriodSelected = { period.value = it })
                }
            }
        }
        compose.onNodeWithContentDescription("Last 7 days").assertIsDisplayed().performClick()
        assertEquals(InsightPeriod.SevenDays, period.value)
        compose.onNodeWithContentDescription("Last 7 days").assertIsSelected()
        capture("insights-large-text")
        compose.onNodeWithText("Duration").performScrollTo().assertIsDisplayed()
        capture("insights-metrics-large-text")
    }

    @Test
    @Config(qualifiers = "en-rGB-w393dp-h851dp-xxhdpi")
    fun insightsKeepsTwoMetricsPerRowOnANormalPhone() {
        compose.setContent { Rs457Theme(dynamicColor = false) {
            InsightsScreen(insights = AppUiFixture.state(MainUiState()).insights, units = DistanceUnits.Metric,
                selectedPeriod = InsightPeriod.ThirtyDays, onPeriodSelected = {})
        } }
        compose.onNodeWithText("Rides").performScrollTo()
        val rides = compose.onNodeWithText("Rides").getUnclippedBoundsInRoot()
        val time = compose.onNodeWithText("Ride time").getUnclippedBoundsInRoot()
        assertEquals(rides.top.value, time.top.value, 1f)
        assertTrue(time.left > rides.right)
        capture("insights-two-columns")
        compose.onNodeWithText("Records").performScrollTo().assertIsDisplayed()
    }

    @Test fun rideDetailShowsItsFiguresAndOpensTheParkingLocation() {
        val chart = TelemetryChartData(emptyList(), emptyList())
        var parking = 0
        compose.setContent { Rs457Theme(dynamicColor = false) { Surface(Modifier.fillMaxSize()) {
            RideDetailContent(RideDetailUiData(AppUiFixture.ride.copy(endLatitude = 13.05, endLongitude = 80.28),
                false, false, emptyList(), chart, chart, chart, emptyList()), DistanceUnits.Metric,
                onOpenParking = { parking++ })
        } } }
        compose.onNodeWithText("Home → Marina Beach").assertIsDisplayed()
        compose.onNodeWithText("Top speed").assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Parking location"))
        compose.onNodeWithText("Parking location").performClick()
        assertEquals(1, parking)
        capture("ride-details")
    }

    @Test fun insightsEmptyPeriodShowsOneClearState() {
        compose.setContent { Rs457Theme(dynamicColor = false) {
            InsightsScreen(insights = RideInsights(), units = DistanceUnits.Metric,
                selectedPeriod = InsightPeriod.SevenDays, onPeriodSelected = {})
        } }
        compose.onNodeWithText("No rides in this period").assertIsDisplayed()
        compose.onNodeWithText("Averages").assertDoesNotExist()
        compose.onNodeWithContentDescription("Last 7 days").assertExists()
    }

    @Test fun settingsCarryTheMotorcycleIdentityAndSetupRows() {
        var navigation = 0; var permissions = 0; var developer = 0
        compose.setContent { Rs457Theme(dynamicColor = false) {
            val ui = MainUiState(selectedDestination = TopLevelDestination.Settings)
            val actions = AppUiFixture.actions().copy(
                onOpenNavigationSettings = { navigation++ },
                onOpenAppPermissions = { permissions++ },
                onOpenDiagnostics = { developer++ },
            )
            MainScreen(ui, actions) { modifier -> MainScreenContent(modifier, AppUiFixture.state(ui), actions) }
        } }
        compose.onNodeWithText("TESTVIN123456789").assertIsDisplayed()
        compose.onNodeWithText("Google Navigation key").performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Battery use"))
        compose.onNodeWithText("Battery use").performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Developer tools"))
        compose.onNodeWithText("Developer tools").performClick()
        assertEquals(1, navigation); assertEquals(1, permissions); assertEquals(1, developer)
    }

    @Test
    @Config(qualifiers = "en-rGB-w320dp-h740dp-xxhdpi")
    fun navigationSetupConfirmsKeyRemovalAtLargeText() {
        RuntimeEnvironment.setFontScale(2f)
        var removed = 0
        compose.setContent { Rs457Theme(dynamicColor = false) { Surface(Modifier.fillMaxSize()) {
            NavigationSettingsScreen(state = NavigationKeyUiState(isConfigured = true, maskedKey = "•••• 1234"),
                onSave = {}, onRemove = { removed++ }, onTest = {})
        } } }
        val removeButton = hasText("Remove") and hasClickAction() and !hasAnyAncestor(isDialog())
        compose.onNode(removeButton).performScrollTo().performClick()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(0, removed)
        capture("navigation-key-large-text")
        compose.onNode(removeButton).performScrollTo().performClick()
        compose.onNode(hasText("Remove") and hasAnyAncestor(isDialog())).performClick()
        assertEquals(1, removed)
    }

    @Test
    @Config(qualifiers = "en-rGB-w320dp-h740dp-xxhdpi")
    fun settingsChoicesUseAnAccessibleDialogAtLargeTextSizes() {
        RuntimeEnvironment.setFontScale(2f)
        val selected = mutableStateOf(ThemeMode.System)
        compose.setContent { Rs457Theme(dynamicColor = false) { Surface(Modifier.fillMaxSize()) {
            androidx.compose.foundation.layout.Column {
                com.spaceboy.ridebuddy.ui.components.SettingsPickerRow(title = "Theme", choices = ThemeMode.entries,
                    selectedChoice = selected.value, icon = androidx.compose.material.icons.Icons.Outlined.Palette,
                    onSelected = { selected.value = it })
            }
        } } }
        compose.onNodeWithText("Theme").performClick()
        capture("settings-choice-large-text", dialog = true)
        compose.onNodeWithText("Dark").performClick()
        assertEquals(ThemeMode.Dark, selected.value)
    }

    @Test
    @Config(qualifiers = "en-rGB-w320dp-h740dp-xxhdpi")
    fun rideDetailFitsLargeTextAndHidesUnknownParking() {
        RuntimeEnvironment.setFontScale(2f)
        val chart = TelemetryChartData(emptyList(), emptyList())
        compose.setContent { Rs457Theme(dynamicColor = false) { Surface(Modifier.fillMaxSize()) {
            RideDetailContent(RideDetailUiData(AppUiFixture.ride, false, false, emptyList(), chart, chart, chart, emptyList()),
                DistanceUnits.Metric, onOpenParking = {})
        } } }
        compose.onNodeWithText("Parking location").assertDoesNotExist()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Charts for this ride", substring = true))
        capture("ride-details-actions-large-text")
    }

    @Test
    @Config(qualifiers = "en-rGB-w320dp-h740dp-xxhdpi")
    fun liveNavigationActionsAndThreeDigitSpeedFitLargeText() {
        RuntimeEnvironment.setFontScale(2f)
        var stopped = 0
        var opened = 0
        compose.setContent { Rs457Theme(dynamicColor = false) { Surface(Modifier.fillMaxSize()) {
            LiveCardFixture.LiveScreenUnderTest(ride = LiveCardFixture.recording(),
                frame = LiveCardFixture.Frame.copy(speedKilometresPerHour = 183.0),
                guidance = com.spaceboy.ridebuddy.core.navigation.GuidanceState(active = true,
                    instruction = "Turn left onto Beach Road", distanceToManeuverMetres = 200),
                onStopNavigation = { stopped++ }, onOpenActiveNavigation = { opened++ })
        } } }
        compose.onNodeWithText("183").assertExists()
        capture("live-three-digit-large-text")
        compose.onNodeWithText("End route").performScrollTo().performClick()
        compose.onNodeWithText("Full map").performScrollTo().performClick()
        assertEquals(1, stopped)
        assertEquals(1, opened)
        capture("live-navigation-actions-large-text")
    }

    @Test fun sliderShowsItsCurrentValueAndCommitsTheCompletedAdjustment() {
        var changes = 0
        var saved = 60f
        compose.setContent { Rs457Theme(dynamicColor = false) {
            com.spaceboy.ridebuddy.ui.components.SettingsSliderRow(
                title = "Parking delay", valueLabel = { "${it.toInt()} s" }, value = 60f,
                range = 30f..300f, steps = 8,
                onValueChange = { saved = it; changes++ },
            )
        } }
        compose.onNodeWithContentDescription("Parking delay").performTouchInput { click(center) }
        assertEquals(1, changes)
        assertTrue(saved > 60f)
        compose.onNodeWithText("${saved.toInt()} s").assertExists()
    }

    @Test
    @Config(qualifiers = "en-rGB-w320dp-h740dp-xxhdpi")
    fun mainNavigationRemainsUsableAtLargeTextSizes() {
        RuntimeEnvironment.setFontScale(2f)
        compose.setContent { Rs457Theme(dynamicColor = false) {
            val ui = MainUiState(selectedDestination = TopLevelDestination.Settings)
            val actions = AppUiFixture.actions()
            MainScreen(ui, actions) { modifier -> MainScreenContent(modifier, AppUiFixture.state(ui), actions) }
        } }
        val root = compose.onRoot().getUnclippedBoundsInRoot()
        for (destination in TopLevelDestination.entries) {
            val item = compose.onNodeWithTag("top-level-${destination.name}").assertIsDisplayed()
                .getUnclippedBoundsInRoot()
            assertTrue((item.right - item.left).value >= 48f && (item.bottom - item.top).value >= 48f)
            assertTrue(item.left >= root.left && item.right <= root.right)
        }
        capture("app-navigation-large-text")
    }

    private fun capture(name: String, dialog: Boolean = false) {
        val node = if (dialog) compose.onNode(isDialog()) else compose.onRoot()
        val bitmap = node.captureToImage().asAndroidBitmap()
        val file = File("build/outputs/renders/$name.png").also { requireNotNull(it.parentFile).mkdirs() }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(file.length() > 0)
    }
}
