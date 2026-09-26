package com.spaceboy.ridebuddy.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
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
 * Draws the live details sheet, top and bottom, into `app/build/outputs/renders/`.
 *
 * The sheet is what the card's one action opens, so reviewing the card means reviewing this
 * too. It composes into its own window, which the screen's root never draws — hence the
 * capture goes through the sheet's own node, found by the pane title only it carries. Two
 * shots because the sheet is now one scroll rather than three levels, and no single frame
 * holds all of it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-night-xxhdpi")
class LiveDetailsSheetRenderTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun top() {
        openSheet()
        capture("sheet-top")
    }

    @Test
    fun scrolledToCharts() {
        openSheet()
        // Scrolled by offset rather than to a named node: the charts sit below the fold at
        // any screen size, and an offset does not depend on a label that may be reworded.
        composeRule.onNode(hasAnyAncestor(isDialog()) and SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollBy))
            .performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 1_400f) }
        composeRule.waitForIdle()
        capture("sheet-charts")
    }

    private fun openSheet() {
        composeRule.setContent {
            Rs457Theme(themeMode = ThemeMode.Dark, dynamicColor = false) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    LiveCardFixture.LiveScreenUnderTest(
                        ride = LiveCardFixture.recording(),
                        samples = LiveCardFixture.samples(),
                    )
                }
            }
        }
        composeRule.onNodeWithText("Live details").performClick()
        composeRule.waitForIdle()
    }

    private fun capture(name: String) {
        val bitmap = composeRule.onNode(IsBottomSheet).captureToImage().asAndroidBitmap()
        val file = File(File("build/outputs/renders").apply { mkdirs() }, "$name.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }

        assertTrue("nothing was drawn to $file", file.length() > 0L)
        assertTrue("the sheet was captured at the wrong size", bitmap.width > 600 && bitmap.height > 300)
    }

    private companion object {
        /** The sheet is the one node on screen that declares a pane title. */
        val IsBottomSheet = SemanticsMatcher.keyIsDefined(SemanticsProperties.PaneTitle)
    }
}
