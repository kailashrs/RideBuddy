package com.spaceboy.ridebuddy.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.spaceboy.ridebuddy.*
import com.spaceboy.ridebuddy.data.*
import com.spaceboy.ridebuddy.ui.theme.Rs457Theme
import com.spaceboy.ridebuddy.ui.screens.HistoryDateRangeDialog
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Vivo V2454A, read over ADB: 1440x3168, 640 dpi, Android 16, en-IN, font scale 1.0. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rIN-w360dp-h792dp-640dpi")
class DeviceScreenUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun mainScreensAndInsightsGridFitTheDeviceInBothThemes() {
        RuntimeEnvironment.setFontScale(1f)
        val ui = mutableStateOf(MainUiState(navigationKey = NavigationKeyUiState(isConfigured = true, maskedKey = "•••• 1234")))
        val dark = mutableStateOf(false)
        compose.setContent {
            Rs457Theme(themeMode = if (dark.value) ThemeMode.Dark else ThemeMode.Light, dynamicColor = false) {
                val actions = AppUiFixture.actions { ui.value = ui.value.copy(selectedDestination = it) }
                MainScreen(ui.value, actions) { modifier -> MainScreenContent(modifier, AppUiFixture.state(ui.value), actions) }
            }
        }
        for (night in listOf(false, true)) {
            compose.runOnIdle { dark.value = night }
            val theme = if (night) "dark" else "light"
            for (destination in TopLevelDestination.entries) {
                compose.onNodeWithTag("top-level-${destination.name}").performClick()
                compose.waitForIdle()
                if (destination == TopLevelDestination.History) {
                    compose.waitUntil(5_000) { compose.onAllNodesWithText("1 ride ·", substring = true).fetchSemanticsNodes().isNotEmpty() }
                }
                capture("${destination.name.lowercase()}-$theme")
                if (destination == TopLevelDestination.Insights) {
                    compose.onNodeWithText("Rides", useUnmergedTree = true).performScrollTo()
                    val rides = compose.onNodeWithText("Rides", useUnmergedTree = true).getUnclippedBoundsInRoot()
                    val time = compose.onNodeWithText("Ride time", useUnmergedTree = true).getUnclippedBoundsInRoot()
                    assertEquals(rides.top.value, time.top.value, 1f)
                    assertTrue(time.left > rides.right)
                    capture("insights-metrics-$theme")
                    compose.onNodeWithText("Records").performScrollTo().assertIsDisplayed()
                    capture("insights-footer-$theme")
                }
            }
        }
    }

    @Test fun rideDetailSummaryFitsTheDeviceWidth() {
        RuntimeEnvironment.setFontScale(1f)
        val dark = mutableStateOf(false)
        val chart = TelemetryChartData(emptyList(), emptyList())
        var parking = 0
        compose.setContent {
            Rs457Theme(themeMode = if (dark.value) ThemeMode.Dark else ThemeMode.Light, dynamicColor = false) {
                Surface(Modifier.fillMaxSize()) {
                    RideDetailContent(
                        RideDetailUiData(AppUiFixture.ride.copy(endLatitude = 13.05, endLongitude = 80.28),
                            false, false, emptyList(), chart, chart, chart, emptyList()),
                        DistanceUnits.Metric, onOpenParking = { parking++ },
                    )
                }
            }
        }
        for (night in listOf(false, true)) {
            compose.runOnIdle { dark.value = night }
            val distance = compose.onNodeWithText("Distance").getUnclippedBoundsInRoot()
            val duration = compose.onNodeWithText("Duration").getUnclippedBoundsInRoot()
            assertEquals(distance.top.value, duration.top.value, 1f)
            compose.onNode(hasScrollAction()).performScrollToNode(hasText("Parking location"))
            compose.onNodeWithText("Parking location").performClick()
            capture("ride-detail-${if (night) "dark" else "light"}")
        }
        assertEquals(2, parking)
    }

    @Test fun selectedDateRangeFitsTheDeviceDialog() {
        val range = HistoryFilter.Dates(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30))
        var applied: HistoryFilter.Dates? = null
        compose.setContent { Rs457Theme(themeMode = ThemeMode.Dark, dynamicColor = false) {
            HistoryDateRangeDialog(range, range.endInclusive, {}, { applied = it })
        } }
        val bitmap = compose.onNode(isDialog()).captureToImage().asAndroidBitmap()
        File("build/outputs/renders/vivo-date-picker-selected.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onAllNodes(hasText("30 Sept 2026"), useUnmergedTree = true)[0]
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals("Selected date should fit on one line", 1, layouts.single().lineCount)
        val dialog = compose.onNode(isDialog()).getUnclippedBoundsInRoot()
        assertEquals(360f, (dialog.right - dialog.left).value, 1f)
        assertTrue("The picker should remain a floating modal", (dialog.bottom - dialog.top).value < 650f)
        compose.onNodeWithContentDescription("Switch to text input mode").performClick()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(2)
        compose.onNodeWithContentDescription("Switch to calendar input mode").performClick()
        compose.onNodeWithText("Apply").performClick()
        assertEquals(range, applied)
    }

    private fun capture(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        assertEquals(1440, bitmap.width)
        assertEquals(3168, bitmap.height)
        val file = File("build/outputs/renders/vivo-$name.png")
        requireNotNull(file.parentFile).mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
