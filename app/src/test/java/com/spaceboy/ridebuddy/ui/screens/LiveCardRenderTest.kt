package com.spaceboy.ridebuddy.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.spaceboy.ridebuddy.data.ActiveRide
import com.spaceboy.ridebuddy.data.ThemeMode
import com.spaceboy.ridebuddy.ui.theme.Rs457Theme
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Draws the live screen to PNGs under `app/build/outputs/renders/`.
 *
 * The card is a visual surface, and the only honest way to review one is to look at it —
 * but doing that on a device means an emulator image or an install, neither of which belongs
 * in the ordinary test loop. Robolectric's native graphics mode runs the real Skia pipeline,
 * so `./gradlew testDebugUnitTest` leaves a picture behind. [LiveTelemetryCardTest] is what
 * actually asserts on the layout; this only checks that something was drawn.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-night-xxhdpi")
class LiveCardRenderTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun midRide() {
        render("live-recording", LiveCardFixture.recording())
    }

    @Test
    fun connectedButStopped() {
        render("live-idle", null)
    }

    private fun render(name: String, ride: ActiveRide?) {
        composeRule.setContent {
            Rs457Theme(themeMode = ThemeMode.Dark, dynamicColor = false) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    LiveCardFixture.LiveScreenUnderTest(ride = ride)
                }
            }
        }
        composeRule.waitForIdle()

        val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
        val directory = File("build/outputs/renders").apply { mkdirs() }
        val file = File(directory, "$name.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }

        // A blank or zero-byte file would make a broken pipeline look like a design problem.
        assertTrue("nothing was drawn to $file", file.length() > 0L)
        assertTrue("render is not a phone-sized surface", bitmap.width > 600 && bitmap.height > 1_200)
    }
}
