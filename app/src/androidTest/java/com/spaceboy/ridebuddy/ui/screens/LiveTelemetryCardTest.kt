package com.spaceboy.ridebuddy.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTextExactly
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.spaceboy.ridebuddy.ble.TelemetryFrame
import com.spaceboy.ridebuddy.core.navigation.GuidanceState
import com.spaceboy.ridebuddy.data.ActiveRide
import com.spaceboy.ridebuddy.data.DistanceUnits
import com.spaceboy.ridebuddy.data.LiveRideMetrics
import com.spaceboy.ridebuddy.domain.BikeConnectionState
import com.spaceboy.ridebuddy.domain.BleDiagnostics
import com.spaceboy.ridebuddy.ui.LiveTelemetryStreams
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The live card's arrangement.
 *
 * It carries three kinds of thing — readings, ride state and actions — and the earlier layout
 * ran all three together in one trailing row, where a wrapped "Recording • 3.0 km" read as a
 * third button. These pin the separation, and pin that instantaneous mileage stays off a card
 * meant to be read at a glance.
 */
@RunWith(AndroidJUnit4::class)
class LiveTelemetryCardTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var endedRide = 0
    private var openedDetails = 0

    @Test
    fun readingsWorthGlancingAtAreOnTheCard() {
        show(ride = null)

        composeRule.onNodeWithText("64").assertIsDisplayed()
        composeRule.onNodeWithText("km/h").assertIsDisplayed()
        composeRule.onNodeWithText("RPM").assertIsDisplayed()
        composeRule.onNodeWithText("5420 rpm").assertIsDisplayed()
        composeRule.onNodeWithText("Throttle").assertIsDisplayed()
        composeRule.onNodeWithText("38%").assertIsDisplayed()
    }

    @Test
    fun mileageIsNotOne() {
        // Instantaneous mileage swings with every throttle movement; it only means something
        // once a ride is over, so it belongs to the details sheet and the ride summary.
        show(ride = null)

        composeRule.onNodeWithText("Mileage").assertDoesNotExist()
        composeRule.onNodeWithText("22.4 km/L").assertDoesNotExist()
    }

    @Test
    fun rideStateIsShownAsStateRatherThanAsAnotherButton() {
        show(ride = recording())

        composeRule.onNodeWithText("Recording").assertIsDisplayed()
        composeRule.onNodeWithText("3.0 km").assertIsDisplayed()
        // Neither half of the status does anything when tapped — only the buttons below it do.
        composeRule.onNode(hasClickAction() and hasTextExactly("Recording")).assertDoesNotExist()
        composeRule.onNode(hasClickAction() and hasTextExactly("3.0 km")).assertDoesNotExist()
    }

    @Test
    fun endingARideIsOfferedOnlyWhileOneIsRunning() {
        show(ride = null)

        composeRule.onNodeWithText("End ride").assertDoesNotExist()
        composeRule.onNodeWithText("Recording").assertDoesNotExist()
        // The card's own action stays put whether or not a ride is running, so its position
        // does not move under a thumb when recording starts.
        composeRule.onNodeWithText("Live details").assertIsDisplayed()
    }

    @Test
    fun eachActionReachesItsOwnHandler() {
        show(ride = recording())

        composeRule.onNodeWithText("End ride").performClick()
        composeRule.onNodeWithText("Live details").performClick()

        assertEquals(1, endedRide)
        assertEquals(1, openedDetails)
    }

    private fun recording(): ActiveRide =
        ActiveRide.started(startedAtMillis = 0L, receivedAtElapsedRealtime = 0L, frame = Frame)
            .copy(distanceKilometres = 3.0)

    private fun show(ride: ActiveRide?) {
        composeRule.setContent {
            MaterialTheme {
                LiveScreen(
                    sharedDestination = null,
                    sharedDestinationError = null,
                    isNavigationStarting = false,
                    connectionState = BikeConnectionState.Connected("RS457_TEST", rssi = -60),
                    live = LiveTelemetryStreams(
                        telemetry = MutableStateFlow(Frame),
                        diagnostics = MutableStateFlow(BleDiagnostics()),
                        activeRide = MutableStateFlow(ride),
                        saveFailed = MutableStateFlow(false),
                        rideSamples = MutableStateFlow(emptyList()),
                        rideMetrics = MutableStateFlow(LiveRideMetrics()),
                    ),
                    lastRide = null,
                    guidance = GuidanceState(),
                    units = DistanceUnits.Metric,
                    onConnectBike = {},
                    onDisconnectBike = {},
                    onEndRide = { endedRide++ },
                    onRetryRideSave = {},
                    onStartNavigation = {},
                    onOpenActiveNavigation = {},
                    onStopNavigation = {},
                    onSharedDestinationHandled = {},
                    onCancelNavigationStart = {},
                )
            }
        }
    }

    private companion object {
        val Frame = TelemetryFrame(
            speedKilometresPerHour = 64.0,
            throttlePercent = 38,
            instantaneousMileageKilometresPerLitre = 22.4,
            engineRpm = 5_420L,
        )
    }
}
