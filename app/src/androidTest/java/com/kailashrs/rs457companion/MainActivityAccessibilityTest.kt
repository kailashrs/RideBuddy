package com.spaceboy.ridebuddy

import android.view.View
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.accessibility.AccessibilityChecks
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import org.hamcrest.Matcher
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test

class MainActivityAccessibilityTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun majorScreensPassAccessibilityChecks() {
        composeRule.onNodeWithText("Skip setup").performClick()
        composeRule.onNodeWithText("Live").assertIsDisplayed()
        composeRule.onNodeWithText("History").assertIsDisplayed()
        composeRule.onNodeWithText("Insights").assertIsDisplayed()
        composeRule.onNodeWithText("Info").assertIsDisplayed()
        composeRule.onNodeWithText("Settings").assertIsDisplayed()
        runAccessibilityScan()

        composeRule.onNodeWithText("Settings").performClick()
        composeRule.waitForIdle()
        runAccessibilityScan()

        composeRule.onNodeWithText("Navigation").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Route preferences").assertIsDisplayed()
        runAccessibilityScan()
    }

    private fun runAccessibilityScan() {
        onView(isRoot()).perform(
            object : ViewAction {
                override fun getConstraints(): Matcher<View> = isRoot()

                override fun getDescription(): String = "Run accessibility checks from the root view"

                override fun perform(uiController: UiController, view: View) {
                    uiController.loopMainThreadUntilIdle()
                }
            },
        )
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun enableAccessibilityChecks() {
            AccessibilityChecks.enable().setRunChecksFromRootView(true)
        }
    }
}
