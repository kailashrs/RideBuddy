package com.spaceboy.ridebuddy.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.runtime.mutableStateOf
import com.spaceboy.ridebuddy.domain.BikeConnectionState
import java.io.File
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
class OnboardingCopyTest {
    @get:Rule
    val composeRule = createComposeRule()
    private var bikeIsPaired by mutableStateOf(false)

    @Test
    fun pairingUsesFindMyBikeBeforeAssociationAndConnectAfterward() {
        bikeIsPaired = false
        show()
        repeat(2) { composeRule.onNodeWithText("Continue").performClick() }

        composeRule.onNodeWithText("Pair your motorcycle").assertIsDisplayed()
        composeRule.onNodeWithText("Find my bike").assertIsDisplayed()
        capture("onboarding-bike-unpaired")

        bikeIsPaired = true
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Motorcycle paired").assertIsDisplayed()
        composeRule.onNodeWithText("Connect").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Find my bike").assertDoesNotExist()
        capture("onboarding-bike-paired")
    }

    @Test
    fun notificationAccessIsExplainedForAppAlertsAndNotCallControl() {
        bikeIsPaired = true
        show()
        repeat(1) { composeRule.onNodeWithText("Continue").performClick() }

        composeRule.onNodeWithText("Notification access").assertIsDisplayed()
        composeRule.onNodeWithText("Shows app notifications on the bike").assertIsDisplayed()
        composeRule.onNodeWithText("Allow notification access to display incoming caller names, call controls, and weather alerts directly on your motorcycle screen.").assertDoesNotExist()
        capture("onboarding-permissions")
    }

    private fun show() {
        composeRule.setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    OnboardingScreen(
                        connectionState = BikeConnectionState.Disconnected,
                        bikeAssociated = bikeIsPaired,
                        nearbyDeviceAccessGranted = true,
                        preciseLocationGranted = false,
                        notificationAccessEnabled = false,
                        appNotificationPermissionGranted = false,
                        telemetryReceiving = false,
                        authenticated = false,
                        navigationConfigured = false,
                        onRequestNearbyDeviceAccess = {},
                        onRequestPreciseLocation = {},
                        onAssociateBike = {},
                        onOpenNotificationAccess = {},
                        onRequestAppNotificationPermission = {},
                        onSetUpNavigation = {},
                        onComplete = {},
                    )
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
        assertTrue("onboarding render is empty: $name", file.length() > 0L)
        assertTrue("onboarding screen should render at phone scale", bitmap.height > 1_000)
    }
}
