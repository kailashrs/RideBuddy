package com.spaceboy.ridebuddy.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTextExactly
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.spaceboy.ridebuddy.data.ActiveRide
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The live card's arrangement.
 *
 * It carries three kinds of thing — readings, ride state and actions — and the earlier layout
 * ran all three together in one trailing row, where a wrapped "Recording • 3.0 km" read as a
 * third button. These pin the separation, and pin that instantaneous mileage stays off a card
 * meant to be read at a glance.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class LiveTelemetryCardTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var endedRide = 0

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
        show(ride = LiveCardFixture.recording())

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
    fun endingARideReachesItsHandlerAndNothingElseDoes() {
        show(ride = LiveCardFixture.recording())

        composeRule.onNodeWithText("End ride").performClick()

        assertEquals(1, endedRide)
    }

    @Test
    fun theCardsOwnActionOpensTheDetailsSheet() {
        show(ride = LiveCardFixture.recording())

        composeRule.onNodeWithText("Live details").performClick()

        // The sheet's own detail-level control, which exists nowhere else on the screen.
        composeRule.onNodeWithText("Glance").assertIsDisplayed()
        assertEquals(0, endedRide)
    }

    private fun show(ride: ActiveRide?) {
        composeRule.setContent {
            MaterialTheme {
                LiveCardFixture.LiveScreenUnderTest(ride = ride, onEndRide = { endedRide++ })
            }
        }
    }
}
