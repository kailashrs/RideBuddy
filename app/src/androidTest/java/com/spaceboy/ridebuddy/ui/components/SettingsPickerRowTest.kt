package com.spaceboy.ridebuddy.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Storage
import com.spaceboy.ridebuddy.data.SampleRetention
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The retention choice outgrew a segmented button — five labels, each a phrase, split one
 * character per line. These cover the component that replaced it.
 */
@RunWith(AndroidJUnit4::class)
class SettingsPickerRowTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var chosen: SampleRetention? = null

    @Test
    fun theRowCarriesTheCurrentChoiceWithoutOpeningAnything() {
        show(SampleRetention.OneYear)

        composeRule.onNodeWithText("Keep detailed telemetry").assertIsDisplayed()
        composeRule.onNodeWithText("1 year").assertIsDisplayed()
        // Nothing is open, so the other options are nowhere on screen.
        composeRule.onNodeWithText("Keep everything").assertDoesNotExist()
    }

    @Test
    fun everyOptionIsOfferedInFullOnceThePickerOpens() {
        // Starting on the shortest window keeps each option's text unique on screen, so a
        // match below can only be the dialog's.
        show(SampleRetention.ThirtyDays)

        composeRule.onNodeWithText("Keep detailed telemetry").performClick()

        SampleRetention.entries.filter { it != SampleRetention.ThirtyDays }.forEach {
            composeRule.onNodeWithText(it.label).assertIsDisplayed()
        }
    }

    @Test
    fun choosingAnOptionReportsItAndClosesThePicker() {
        show(SampleRetention.ThirtyDays)
        composeRule.onNodeWithText("Keep detailed telemetry").performClick()

        composeRule.onNodeWithText("Keep everything").performClick()

        assertEquals(SampleRetention.Forever, chosen)
        composeRule.onNodeWithText("Cancel").assertDoesNotExist()
    }

    @Test
    fun cancellingLeavesTheChoiceAlone() {
        show(SampleRetention.ThirtyDays)
        composeRule.onNodeWithText("Keep detailed telemetry").performClick()

        composeRule.onNodeWithText("Cancel").performClick()

        assertNull(chosen)
        composeRule.onNodeWithText("Keep everything").assertDoesNotExist()
    }

    private fun show(selected: SampleRetention) {
        composeRule.setContent {
            MaterialTheme {
                SettingsPickerRow(
                    icon = Icons.Outlined.Storage,
                    title = "Keep detailed telemetry",
                    choices = SampleRetention.entries,
                    selectedChoice = selected,
                    choiceLabel = SampleRetention::label,
                    onSelected = { chosen = it },
                )
            }
        }
    }
}
