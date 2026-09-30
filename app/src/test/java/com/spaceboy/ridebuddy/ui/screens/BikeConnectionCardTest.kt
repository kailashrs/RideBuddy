package com.spaceboy.ridebuddy.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTextExactly
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.spaceboy.ridebuddy.domain.BikeConnectionState
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class BikeConnectionCardTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun unpairedBikeOffersFindMyBikeWithoutProximityAdvice() {
        var finds = 0
        show(
            state = BikeConnectionState.Disconnected,
            associated = false,
            onConnect = { finds++ },
        )

        composeRule.onNodeWithText("Not paired").assertIsDisplayed()
        composeRule.onNode(hasClickAction() and hasTextExactly("Find my bike")).assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Connect").assertDoesNotExist()
        composeRule.onNodeWithText("Switch the bike on and keep it nearby").assertDoesNotExist()
        assertEquals(1, finds)
        capture("connection-unpaired")
    }

    @Test
    fun pairedBikeOffersConnectAndDoesNotCallItFindMyBike() {
        var connects = 0
        show(
            state = BikeConnectionState.Disconnected,
            associated = true,
            onConnect = { connects++ },
        )

        composeRule.onNodeWithText("Not connected").assertIsDisplayed()
        composeRule.onNode(hasClickAction() and hasTextExactly("Connect")).assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Find my bike").assertDoesNotExist()
        composeRule.onNodeWithText("Switch the bike on and keep it nearby").assertDoesNotExist()
        assertEquals(1, connects)
        capture("connection-paired")
    }

    @Test
    fun connectingShowsOnlyProgressAndShortStatus() {
        show(
            state = BikeConnectionState.Connecting("Aprilia RS 457"),
            associated = true,
        )

        composeRule.onNodeWithText("Connecting…").assertIsDisplayed()
        composeRule.onNodeWithText("Find my bike").assertDoesNotExist()
        composeRule.onNodeWithText("Connect").assertDoesNotExist()
        capture("connection-connecting")
    }

    @Test
    fun pairingInProgressShowsStatusWithoutAnotherPairAction() {
        show(
            state = BikeConnectionState.Disconnected,
            associated = false,
            pairingInProgress = true,
        )

        composeRule.onNodeWithText("Finding your bike…").assertIsDisplayed()
        composeRule.onNodeWithText("Find my bike").assertDoesNotExist()
        composeRule.onNodeWithText("Connect").assertDoesNotExist()
        capture("connection-finding-bike")
    }

    @Test
    fun failedConnectionKeepsTechnicalDetailsOutOfTheCard() {
        val userMessage = com.spaceboy.ridebuddy.domain.riderFacingConnectionFailure(
            message = "Link lost while starting service discovery: rejected by the Bluetooth stack (133)",
            category = com.spaceboy.ridebuddy.domain.ConnectionFailureCategory.LinkLost,
        )
        show(
            state = BikeConnectionState.Failed(userMessage, retriesExhausted = true),
            associated = true,
        )

        composeRule.onNodeWithText("Couldn't connect").assertIsDisplayed()
        composeRule.onNode(hasClickAction() and hasTextExactly("Retry")).assertIsDisplayed()
        composeRule.onNode(hasText("GATT", substring = true)).assertDoesNotExist()
        capture("connection-failed")
    }

    private fun show(
        state: BikeConnectionState,
        associated: Boolean,
        pairingInProgress: Boolean = false,
        onConnect: () -> Unit = {},
    ) {
        composeRule.setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                        ConnectionCard(
                            state = state,
                            bikeAssociated = associated,
                            pairingInProgress = pairingInProgress,
                            onConnectBike = onConnect,
                            onDisconnectBike = {},
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun capture(name: String) {
        val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
        val directory = File("build/outputs/renders").apply { mkdirs() }
        val file = File(directory, "$name.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue("connection render is empty: $name", file.length() > 0L)
        assertTrue("connection card should render at phone scale", bitmap.height > 1_000)
    }
}
