package com.spaceboy.ridebuddy

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.spaceboy.ridebuddy.domain.BikeConnectionState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NavigationStartControlsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun connectionCompletingChangesConnectToGoWithoutStartingIt() {
        val state = mutableStateOf<BikeConnectionState>(BikeConnectionState.Disconnected)
        var connects = 0
        var starts = 0
        compose.setContent {
            MaterialTheme { Column {
                NavigationStartControls(true, state.value, true, false, { starts++ }, { connects++ })
            } }
        }
        compose.onNodeWithText("Connect to start").assertExists()
        compose.onNodeWithText("Connect").assertIsEnabled().performClick()
        assertEquals(1, connects)
        assertEquals(0, starts)
        compose.runOnIdle { state.value = BikeConnectionState.Authenticating("RS 457") }
        compose.onNodeWithText("Connecting…").assertIsNotEnabled()
        compose.onNodeWithText("Go").assertDoesNotExist()
        compose.runOnIdle { state.value = BikeConnectionState.Connected("RS 457", null) }
        compose.onNodeWithText("Connect to start").assertDoesNotExist()
        compose.onNodeWithText("Go").assertIsEnabled()
        assertEquals(0, starts)
        compose.onNodeWithText("Go").performClick()
        assertEquals(1, starts)
    }

    @Test fun unpairedPreviewOffersFindMyBikeAndPendingRouteCannotStart() {
        val ready = mutableStateOf(false)
        var finds = 0
        compose.setContent {
            MaterialTheme { Column {
                NavigationStartControls(ready.value, BikeConnectionState.Disconnected, false, false,
                    { error("No connection") }, { finds++ })
            } }
        }
        compose.onNodeWithText("Finding route…").assertIsNotEnabled()
        compose.runOnIdle { ready.value = true }
        compose.onNodeWithText("Find my bike").assertIsEnabled().performClick()
        assertEquals(1, finds)
        compose.onNodeWithText("Go").assertDoesNotExist()
    }
}
