package com.spaceboy.ridebuddy.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.spaceboy.ridebuddy.AutoStartSharedDestinationRequest
import com.spaceboy.ridebuddy.MainUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class SharedDestinationReplacementTest {
    @get:Rule val compose = createComposeRule()

    @Test fun laterShareReplacesFailedLinkBeforeItsRouteHasResolved() {
        val initial = "bad Maps link"
        val latest = "https://www.google.com/maps?q=12.9716,77.5946"
        val state = mutableStateOf(MainUiState(
            sharedDestination = initial,
            sharedDestinationError = "Could not read that destination",
        ))
        var handled = 0
        compose.setContent {
            MaterialTheme {
                LiveCardFixture.LiveScreenUnderTest(
                    ride = null,
                    sharedDestination = state.value.autoStartSharedDestination?.destination
                        ?: state.value.sharedDestination,
                    sharedDestinationError = state.value.sharedDestinationError,
                    onSharedDestinationHandled = { handled++; state.value = MainUiState() },
                )
            }
        }
        compose.onNodeWithText(initial).assertExists()
        compose.runOnIdle {
            state.value = MainUiState(
                autoStartSharedDestination = AutoStartSharedDestinationRequest(2, latest),
            )
        }
        compose.onNodeWithText(latest).assertExists()
        compose.onNodeWithText(initial).assertDoesNotExist()
        compose.onNodeWithText("Could not read that destination").assertDoesNotExist()

        compose.onNode(hasSetTextAction()).performTextReplacement("edited link")
        compose.onNodeWithText("edited link").assertExists()
        assertEquals(1, handled)
        assertNull(state.value.autoStartSharedDestination)
    }

    @Test fun clearingPendingShareCancelsItAndEmptiesTheField() {
        val latest = "https://www.google.com/maps?q=12.9716,77.5946"
        val state = mutableStateOf(MainUiState(
            autoStartSharedDestination = AutoStartSharedDestinationRequest(3, latest),
        ))
        var handled = 0
        compose.setContent {
            MaterialTheme {
                LiveCardFixture.LiveScreenUnderTest(
                    ride = null,
                    sharedDestination = state.value.autoStartSharedDestination?.destination
                        ?: state.value.sharedDestination,
                    sharedDestinationError = state.value.sharedDestinationError,
                    onSharedDestinationHandled = { handled++; state.value = MainUiState() },
                )
            }
        }
        compose.onNodeWithText(latest).assertExists()
        compose.onNodeWithContentDescription("Clear").performClick()
        compose.onNodeWithText(latest).assertDoesNotExist()
        assertEquals(1, handled)
        assertNull(state.value.autoStartSharedDestination)
    }
}
