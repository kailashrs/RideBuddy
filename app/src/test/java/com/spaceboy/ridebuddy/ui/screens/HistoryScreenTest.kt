package com.spaceboy.ridebuddy.ui.screens

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.spaceboy.ridebuddy.data.DistanceUnits
import com.spaceboy.ridebuddy.data.Ride
import com.spaceboy.ridebuddy.data.ThemeMode
import com.spaceboy.ridebuddy.ui.theme.Rs457Theme
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rGB-w411dp-h891dp-xxhdpi")
class HistoryScreenTest {
    @get:Rule val compose = createComposeRule()
    private val clock = Clock.fixed(Instant.parse("2026-09-30T12:00:00Z"), ZoneOffset.UTC)
    private lateinit var previousZone: TimeZone
    private val rides = listOf(
        ride(1, "2026-09-30T08:15:00Z", 10.0, 30, "Home", "Office"),
        ride(2, "2026-09-29T18:10:00Z", 20.0, 45, "Office", "Park"),
        ride(3, "2026-08-31T09:00:00Z", 30.0, 30, "Park", "Beach"),
        ride(4, "2025-12-31T09:00:00Z", 5.0, 15, "Beach", "Home"),
    )

    @Before fun setTimeZone() { previousZone = TimeZone.getDefault(); TimeZone.setDefault(TimeZone.getTimeZone("UTC")) }
    @After fun restoreTimeZone() { TimeZone.setDefault(previousZone); RuntimeEnvironment.setFontScale(1f) }

    @Test fun groupingSummaryAndDateFilterStayConsistent() {
        var opened: Long? = null
        show(onRide = { opened = it.id })
        awaitText("4 rides · 65.0 km · 2h 0m")
        compose.onNodeWithText("Today").assertIsDisplayed()
        compose.onNodeWithText("Yesterday").assertExists()
        capture("history-light")
        compose.onNodeWithText("Home → Office").performClick()
        assertEquals(1L, opened)
        choose("This month")
        awaitText("2 rides · 30.0 km · 1h 15m")
        compose.onNodeWithText("Park → Beach").assertDoesNotExist()
        choose("All rides")
        awaitText("4 rides · 65.0 km · 2h 0m")
    }

    @Test fun emptyFilteredHistoryKeepsAClearAction() {
        show(rides = listOf(rides[2]))
        awaitText("1 ride · 30.0 km · 30m")
        choose("This month")
        awaitText("No rides in this period")
        compose.onNodeWithText("All rides").assertIsDisplayed()
        capture("history-empty-period")
        choose("All rides")
        awaitText("1 ride · 30.0 km · 30m")
    }

    @Test fun filterSurvivesTabChangesAndSavedStateRestoration() {
        val visible = mutableStateOf(true)
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            Theme {
                val holder = rememberSaveableStateHolder()
                if (visible.value) holder.SaveableStateProvider("history") {
                    HistoryScreen(rides = rides, units = DistanceUnits.Metric, onRideSelected = {}, clock = clock)
                }
            }
        }
        awaitText("4 rides · 65.0 km · 2h 0m")
        choose("This week")
        awaitText("2 rides · 30.0 km · 1h 15m")
        compose.runOnIdle { visible.value = false }
        compose.runOnIdle { visible.value = true }
        awaitText("2 rides · 30.0 km · 1h 15m")
        compose.onNodeWithText("This week").assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        awaitText("2 rides · 30.0 km · 1h 15m")
        compose.onNodeWithText("This week").assertIsDisplayed()
    }

    @Test fun customDateInputAppliesInclusiveRangeAndCancelKeepsSelection() {
        show()
        awaitText("4 rides · 65.0 km · 2h 0m")
        choose("Choose dates")
        compose.onNodeWithText("Apply").assertIsNotEnabled()
        capture("history-date-picker", dialog = true)
        compose.onNodeWithContentDescription("Switch to text input mode").performClick()
        val inputs = compose.onAllNodes(hasSetTextAction())
        inputs[0].performTextReplacement("29092026")
        inputs[1].performTextReplacement("30092026")
        compose.onNodeWithText("Apply").assertIsEnabled().performClick()
        awaitText("2 rides · 30.0 km · 1h 15m")
        capture("history-custom")
        compose.onNode(hasClickAction() and hasText("Sep", substring = true)).performClick()
        compose.onNodeWithText("Cancel").performClick()
        awaitText("2 rides · 30.0 km · 1h 15m")
    }

    @Test fun darkThemeUsesTheSameStructure() {
        show(dark = true)
        awaitText("4 rides · 65.0 km · 2h 0m")
        compose.onNodeWithText("Today").assertIsDisplayed()
        capture("history-dark")
    }

    @Test
    @Config(qualifiers = "en-rGB-w320dp-h740dp-xxhdpi")
    fun largeTextFitsACompactScreen() {
        RuntimeEnvironment.setFontScale(2f)
        show(fontScale = 2f)
        awaitText("4 rides · 65.0 km · 2h 0m")
        compose.onNodeWithText("All rides").assertIsDisplayed()
        capture("history-large-text")
        choose("Choose dates")
        compose.onNodeWithText("Apply").assertIsDisplayed()
        compose.onNodeWithText("Cancel").assertIsDisplayed()
        assertInsideDialog("Apply")
        assertInsideDialog("Cancel")
        capture("history-date-input-large", dialog = true)
        val inputs = compose.onAllNodes(hasSetTextAction())
        inputs[0].performTextReplacement("29092026")
        inputs[1].performTextReplacement("30092026")
        compose.onNodeWithText("Apply").assertIsEnabled().performClick()
        awaitText("2 rides · 30.0 km · 1h 15m")
        capture("history-custom-large-text")
    }

    @Test fun dayHeadingRemainsVisibleWhenScrollingWithinThatDay() {
        val daily = (0..5).map { index -> rides[0].copy(id = 10L + index,
            startedAtMillis = rides[0].startedAtMillis - index * 60_000L) }
        show(rides = daily + rides[1])
        awaitText("Today")
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(4)
        compose.onNodeWithText("Today").assertIsDisplayed()
        capture("history-sticky-header")
    }

    @Test
    @Config(qualifiers = "en-rGB-w740dp-h320dp-xxhdpi")
    fun landscapeDateDialogKeepsItsActionsVisible() {
        show()
        awaitText("4 rides · 65.0 km · 2h 0m")
        choose("Choose dates")
        assertInsideDialog("Apply")
        assertInsideDialog("Cancel")
        capture("history-date-input-landscape", dialog = true)
    }

    @Test fun openCalendarAdaptsWhenTheWindowBecomesCompact() {
        val compact = mutableStateOf(false)
        compose.setContent {
            val actualWindow = LocalWindowInfo.current
            val windowHeight = with(LocalDensity.current) { (if (compact.value) 320.dp else 891.dp).roundToPx() }
            val window = object : WindowInfo by actualWindow {
                override val containerSize = IntSize(actualWindow.containerSize.width, windowHeight)
            }
            CompositionLocalProvider(LocalWindowInfo provides window) {
                Theme {
                    HistoryScreen(rides = rides, units = DistanceUnits.Metric, onRideSelected = {}, clock = clock)
                }
            }
        }
        awaitText("4 rides · 65.0 km · 2h 0m")
        choose("Choose dates")
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.runOnIdle { compact.value = true }
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(2)
        assertInsideDialog("Apply")
        assertInsideDialog("Cancel")
    }

    @Test fun noHistoryDoesNotShowAMeaninglessFilter() {
        show(rides = emptyList())
        compose.onNodeWithText("Your rides will appear here").assertIsDisplayed()
        compose.onNodeWithText("All rides").assertDoesNotExist()
    }

    private fun show(rides: List<Ride> = this.rides, dark: Boolean = false, fontScale: Float = 1f,
        onRide: (Ride) -> Unit = {}) {
        compose.setContent {
            Theme(dark, fontScale) {
                HistoryScreen(rides = rides, units = DistanceUnits.Metric, onRideSelected = onRide, clock = clock)
            }
        }
    }

    @Composable private fun Theme(dark: Boolean = false, fontScale: Float = 1f, content: @Composable () -> Unit) {
        val configuration = Configuration(LocalConfiguration.current).apply { this.fontScale = fontScale }
        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale),
            LocalConfiguration provides configuration) {
            Rs457Theme(themeMode = if (dark) ThemeMode.Dark else ThemeMode.Light, dynamicColor = false) {
                Surface(Modifier.fillMaxSize()) { content() }
            }
        }
    }

    private fun choose(label: String) {
        compose.onNodeWithText(label).performScrollTo().performClick()
    }
    private fun awaitText(text: String) {
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun assertInsideDialog(text: String) {
        val dialog = compose.onNode(isDialog()).getUnclippedBoundsInRoot()
        val action = compose.onNodeWithText(text).getUnclippedBoundsInRoot()
        assertTrue("$text extends past the dialog", action.left >= dialog.left &&
            action.right <= dialog.right && action.top >= dialog.top && action.bottom <= dialog.bottom)
    }
    private fun capture(name: String, dialog: Boolean = false) {
        val bitmap = (if (dialog) compose.onNode(isDialog()) else compose.onRoot()).captureToImage().asAndroidBitmap()
        val file = File("build/outputs/renders/$name.png").also { requireNotNull(it.parentFile).mkdirs() }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(file.length() > 0)
    }
    private fun ride(id: Long, start: String, distance: Double, minutes: Long, from: String, to: String): Ride {
        val millis = Instant.parse(start).toEpochMilli()
        return Ride(id, millis, millis + minutes * 60_000, distance, 35.0, 65.0, 4000.0, 6000, 25.0,
            distance / 25.0, startArea = from, endArea = to)
    }
}
