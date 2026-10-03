package com.spaceboy.ridebuddy.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.spaceboy.ridebuddy.data.DistanceUnits
import com.spaceboy.ridebuddy.data.Ride
import com.spaceboy.ridebuddy.data.ThemeMode
import com.spaceboy.ridebuddy.data.TripSummary
import com.spaceboy.ridebuddy.data.db.Trip
import com.spaceboy.ridebuddy.ui.theme.Rs457Theme
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rGB-w411dp-h891dp-xxhdpi")
class TripsScreenTest {
    @get:Rule val compose = createComposeRule()
    private val clock = Clock.fixed(Instant.parse("2026-09-30T12:00:00Z"), ZoneOffset.UTC)
    private lateinit var previousZone: TimeZone
    private val rides = listOf(
        ride(1, "2026-09-28T06:30:00Z", 142.0, 210, "Chennai", "Puducherry"),
        ride(2, "2026-09-28T16:00:00Z", 18.5, 40, "Puducherry", "Auroville"),
        ride(3, "2026-09-29T07:15:00Z", 96.0, 150, "Auroville", "Tiruvannamalai"),
        ride(4, "2026-09-30T08:15:00Z", 12.0, 30, "Home", "Office"),
    )
    private val trips = listOf(
        TripSummary(Trip(1, "East coast weekend", 0), setOf(1, 2, 3)),
        TripSummary(Trip(2, "Office runs", 0), setOf(4)),
    )

    @Before fun setTimeZone() { previousZone = TimeZone.getDefault(); TimeZone.setDefault(TimeZone.getTimeZone("UTC")) }
    @After fun restoreTimeZone() { TimeZone.setDefault(previousZone) }

    @Test fun historyFiltersRunThisWeekThisMonthAllRidesThenChooseDates() {
        show()
        val lefts = listOf("This week", "This month", "All rides", "Choose dates").map { label ->
            compose.onNodeWithText(label).getUnclippedBoundsInRoot().left
        }
        assertEquals(lefts.sorted(), lefts)
        compose.onNodeWithText("All rides").assertIsSelected()
    }

    @Test fun aTripIsMadeFromAWholeDayAndOneMoreRide() {
        var saved: Triple<Long?, String, Set<Long>>? = null
        show(trips = emptyList(), onSave = { id, name, ids -> saved = Triple(id, name, ids) })
        compose.onNodeWithText("Trips").performClick()
        compose.onNodeWithText("No trips yet").assertIsDisplayed()
        capture("trips-empty")

        compose.onNodeWithText("New trip", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Save").assertIsNotEnabled()
        capture("trip-editor", dialog = true)
        compose.onNode(hasSetTextAction()).performTextInput("East coast weekend")
        // The oldest day holds rides 1 and 2; its checkbox takes both.
        compose.onAllNodes(isHeading()).onLast().performScrollTo().performClick()
        compose.onNodeWithText("2 rides selected").assertExists()
        compose.onNodeWithText("Auroville → Tiruvannamalai").performScrollTo().performClick()
        compose.onNodeWithText("3 rides selected").assertExists()
        capture("trip-editor-selected", dialog = true)
        compose.onNodeWithText("Save").assertIsEnabled().performClick()

        assertEquals(Triple(null, "East coast weekend", setOf(1L, 2L, 3L)), saved)
        compose.onNode(isDialog()).assertDoesNotExist()
    }

    @Test fun aDayCheckboxIsPartlyCheckedUntilEveryRideOfTheDayIs() {
        show(trips = emptyList())
        compose.onNodeWithText("Trips").performClick()
        compose.onNodeWithText("New trip", useUnmergedTree = true).performClick()
        val oldestDay = compose.onAllNodes(isHeading()).onLast()
        compose.onNodeWithText("Chennai → Puducherry").performScrollTo().performClick()
        oldestDay.assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, androidx.compose.ui.state.ToggleableState.Indeterminate))
        oldestDay.performClick()
        oldestDay.assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, androidx.compose.ui.state.ToggleableState.On))
        oldestDay.performClick()
        compose.onNodeWithText("0 rides selected").assertExists()
    }

    @Test fun tripsListTheirTotalsAndOpen() {
        var opened: Long? = null
        show(onTrip = { opened = it.trip.id })
        compose.onNodeWithText("Trips").performClick()
        for (night in listOf(false, true)) {
            compose.runOnIdle { dark = night }
            compose.onNodeWithText("East coast weekend").assertIsDisplayed()
            compose.onNodeWithText("256.5 km").assertIsDisplayed()
            capture("trips-list-${if (night) "dark" else "light"}")
        }
        compose.onNodeWithText("East coast weekend").performClick()
        assertEquals(1L, opened)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test fun aTripShowsItsFiguresThenItsRides() {
        var opened: Long? = null
        compose.setContent {
            Theme {
                Scaffold(topBar = { TopAppBar(title = { Text("East coast weekend") }) }) { padding ->
                    TripDetailContent(rides.take(3), DistanceUnits.Metric, { opened = it.id }, Modifier.padding(padding))
                }
            }
        }
        for (night in listOf(false, true)) {
            compose.runOnIdle { dark = night }
            compose.onNodeWithText("256.5 km").assertIsDisplayed()
            compose.onNodeWithText("28–29 Sept · 3 rides", substring = true).assertIsDisplayed()
            capture("trip-detail-${if (night) "dark" else "light"}")
        }
        compose.onNodeWithText("Auroville → Tiruvannamalai").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(3L, opened)
        capture("trip-detail-rides")
    }

    @Test fun aTripWhoseRidesWereAllDeletedSaysSo() {
        compose.setContent { Theme { TripDetailContent(emptyList(), DistanceUnits.Metric, {}) } }
        compose.onNodeWithText("No rides in this trip").assertIsDisplayed()
    }

    private var dark by mutableStateOf(false)

    private fun show(
        trips: List<TripSummary> = this.trips,
        onTrip: (TripSummary) -> Unit = {},
        onSave: (Long?, String, Set<Long>) -> Unit = { _, _, _ -> },
    ) {
        compose.setContent {
            Theme {
                HistoryScreen(rides = rides, units = DistanceUnits.Metric, onRideSelected = {}, trips = trips,
                    onTripSelected = onTrip, onSaveTrip = onSave, clock = clock)
            }
        }
        compose.waitForIdle()
    }

    @Composable private fun Theme(content: @Composable () -> Unit) {
        Rs457Theme(themeMode = if (dark) ThemeMode.Dark else ThemeMode.Light, dynamicColor = false) {
            Surface(Modifier.fillMaxSize()) { content() }
        }
    }

    private fun capture(name: String, dialog: Boolean = false) {
        val bitmap = (if (dialog) compose.onNode(isDialog()) else compose.onRoot()).captureToImage().asAndroidBitmap()
        val file = File("build/outputs/renders/$name.png").also { requireNotNull(it.parentFile).mkdirs() }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(file.length() > 0)
    }

    private fun ride(id: Long, start: String, distance: Double, minutes: Long, from: String, to: String): Ride {
        val millis = Instant.parse(start).toEpochMilli()
        return Ride(id, millis, millis + minutes * 60_000, distance, distance / (minutes / 60.0), distance + 40, 4200.0, 7500, 28.0,
            distance / 28.0, startArea = from, endArea = to)
    }
}
