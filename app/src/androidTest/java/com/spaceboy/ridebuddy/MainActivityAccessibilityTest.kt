package com.spaceboy.ridebuddy

import android.content.Context
import android.content.SharedPreferences
import android.view.View
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.accessibility.AccessibilityChecks
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import androidx.test.platform.app.InstrumentationRegistry
import com.spaceboy.ridebuddy.data.AppSettings
import com.spaceboy.ridebuddy.data.AppSettingsRepository
import org.hamcrest.Matcher
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.rules.TestRule

class MainActivityAccessibilityTest {
    private val appSettingsIsolation = AppSettingsIsolationRule()
    private val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: TestRule = RuleChain.outerRule(appSettingsIsolation).around(composeRule)

    @Test
    fun majorScreensPassAccessibilityChecks() {
        composeRule.onNodeWithText("Skip setup").assertIsDisplayed()
        runAccessibilityScan()
        composeRule.onNodeWithText("Skip setup").performClick()
        composeRule.waitForIdle()

        listOf("Live", "History", "Insights", "Info", "More").forEach { destination ->
            composeRule.onNodeWithTag("top-level-$destination").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("top-level-$destination").assertIsDisplayed()
            runAccessibilityScan()
        }

        composeRule.onNodeWithText("Navigation").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Route preferences").assertIsDisplayed()
        runAccessibilityScan()

        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.onNodeWithText("Connection details").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Connection state, GATT services, and recent activity").assertIsDisplayed()
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

private class AppSettingsIsolationRule : ExternalResource() {
    private lateinit var preferences: SharedPreferences
    private lateinit var repository: AppSettingsRepository
    private lateinit var originalSettings: AppSettings
    private var originalPreferences: Map<String, *> = emptyMap<String, Any?>()

    override fun before() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        preferences = context.getSharedPreferences(AppSettingsPreferencesName, Context.MODE_PRIVATE)
        originalPreferences = HashMap(preferences.all)

        val application = context.applicationContext
        repository = context.appContainer.appSettings
        originalSettings = repository.settings.value
        repository.update { settings -> settings.copy(onboardingComplete = false) }
    }

    override fun after() {
        repository.update { originalSettings }
        val editor = preferences.edit().clear()
        originalPreferences.forEach { (key, value) ->
            when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is Float -> editor.putFloat(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is String -> editor.putString(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            }
        }
        check(editor.commit()) { "Could not restore app settings after accessibility test" }
    }

    private companion object {
        const val AppSettingsPreferencesName = "app_settings"
    }
}
