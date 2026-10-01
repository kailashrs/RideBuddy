package com.spaceboy.ridebuddy.ui.screens

import androidx.compose.material3.Card
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.spaceboy.ridebuddy.data.ThemeMode
import com.spaceboy.ridebuddy.data.db.Destination
import com.spaceboy.ridebuddy.ui.AppUiFixture
import com.spaceboy.ridebuddy.ui.theme.Rs457Theme
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
@Config(sdk = [36], qualifiers = "en-rIN-w360dp-h792dp-640dpi")
class DestinationListTest {
    @get:Rule val compose = createComposeRule()

    private val navigated = mutableListOf<Destination>()
    private val renamed = mutableListOf<Pair<Long, String>>()
    private val deleted = mutableListOf<Long>()
    private var mapsOpened = 0

    private fun show(destinations: List<Destination> = AppUiFixture.destinations) = compose.setContent {
        Rs457Theme(themeMode = ThemeMode.Light, dynamicColor = false) {
            Card {
                DestinationList(destinations, { navigated += it }, { mapsOpened++ }, { id, name -> renamed += id to name }, { deleted += it })
            }
        }
    }

    @Test fun three_most_frequent_recents_come_first_then_saved_places() {
        show()
        val rows = listOf("Phoenix Marketcity", "Marina Beach", "12 Anna Salai", "Home", "Mum's place")
        val tops = rows.map { compose.onNodeWithText(it).fetchSemanticsNode().boundsInRoot.top }
        assertEquals(tops.sorted(), tops)
        // Fourth by trips, so left off the card.
        assertTrue(compose.onAllNodesWithText("Velachery").fetchSemanticsNodes().isEmpty())
        // A saved place shows its name, with the actual place beneath it.
        compose.onNodeWithText("4 Beach Road, Besant Nagar").fetchSemanticsNode()
        compose.onNodeWithText("14 trips", substring = true).fetchSemanticsNode()
    }

    @Test fun tapping_a_place_starts_a_route_there_and_the_maps_row_opens_maps() {
        show()
        compose.onNodeWithText("Home").performClick()
        compose.onNodeWithText("Find a place in Google Maps").performClick()
        assertEquals("Home", navigated.single().savedName)
        assertEquals(1, mapsOpened)
    }

    @Test fun deleting_a_saved_place_is_confirmed_and_removing_a_recent_is_not() {
        show()
        compose.onNodeWithContentDescription("More options for Mum's place").performClick()
        compose.onNodeWithText("Delete").performClick()
        assertTrue(deleted.isEmpty())
        compose.onNode(hasText("Delete") and hasAnyAncestor(isDialog())).performClick()
        compose.onNodeWithContentDescription("More options for Phoenix Marketcity").performClick()
        compose.onNodeWithText("Remove").performClick()
        assertEquals(listOf(6L, 1L), deleted)
    }

    @Test fun with_nothing_yet_the_card_explains_where_places_come_from() {
        show(emptyList())
        compose.onNodeWithText("No places yet").fetchSemanticsNode()
        compose.onNodeWithText("Find a place in Google Maps").fetchSemanticsNode()
    }
}
