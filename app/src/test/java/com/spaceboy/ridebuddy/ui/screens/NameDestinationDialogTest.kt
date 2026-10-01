package com.spaceboy.ridebuddy.ui.screens

import androidx.compose.material3.Card
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.spaceboy.ridebuddy.ui.AppUiFixture
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The naming dialog, on Robolectric's default configuration: under the device-sized qualifiers
 * the other destination tests use, a dialog's auto-focused text field never lets the Compose test
 * clock go idle. Nothing in the app depends on those qualifiers, so the dialog is checked here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NameDestinationDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test fun saving_a_recent_place_takes_the_name_typed() {
        val renamed = mutableListOf<Pair<Long, String>>()
        compose.setContent {
            Card { DestinationList(AppUiFixture.destinations, {}, {}, { id, name -> renamed += id to name }, {}) }
        }
        compose.onNodeWithContentDescription("More options for Marina Beach").performClick()
        compose.onNodeWithText("Save").performClick()
        val field = compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog()))
        field.performTextReplacement("Beach")
        compose.onNode(hasText("Save") and hasAnyAncestor(isDialog())).performClick()
        assertEquals(listOf(2L to "Beach"), renamed)
    }

    @Test fun a_blank_name_cannot_be_saved() {
        compose.setContent { NameDestinationDialog("Save place", "", {}, {}) }
        compose.onNode(hasText("Save") and hasAnyAncestor(isDialog())).assertIsNotEnabled()
    }
}
