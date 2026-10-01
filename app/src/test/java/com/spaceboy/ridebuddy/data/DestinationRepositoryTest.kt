package com.spaceboy.ridebuddy.data

import androidx.room.Room
import com.spaceboy.ridebuddy.core.navigation.NavigationDestination
import com.spaceboy.ridebuddy.data.db.Destination
import com.spaceboy.ridebuddy.data.db.RideBuddyDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DestinationRepositoryTest {
    private val database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), RideBuddyDatabase::class.java)
        .allowMainThreadQueries().build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private var clock = 1_000L
    private var addressLookups = 0
    private val repository = DestinationRepository(database.destinations(), scope, { _, _ ->
        addressLookups++
        "12 Anna Salai"
    }) { clock }

    @After fun close() { scope.cancel(); database.close() }

    private suspend fun all() = database.destinations().all()

    @Test fun shares_of_the_same_place_count_as_one_destination() = runBlocking {
        repository.recordTrip(NavigationDestination(13.0, 80.0, "Café"))
        clock += 1
        // About 30 m away: the same café, shared again.
        repository.recordTrip(NavigationDestination(13.00027, 80.0, "Café"))
        repository.recordTrip(NavigationDestination(13.01, 80.0, "Elsewhere"))

        val cafe = all().single { it.placeName == "Café" }
        assertEquals(2, cafe.tripCount)
        assertEquals(1_001L, cafe.lastTripAtMillis)
        assertEquals(2, all().size)
    }

    @Test fun recents_are_most_frequent_first_then_most_recent_and_leave_saved_out() {
        val places = listOf(
            Destination(1, 0.0, 0.0, "Once, long ago", tripCount = 1, lastTripAtMillis = 1),
            Destination(2, 0.0, 0.0, "Often", tripCount = 5, lastTripAtMillis = 2),
            Destination(3, 0.0, 0.0, "Once, lately", tripCount = 1, lastTripAtMillis = 9),
            Destination(4, 0.0, 0.0, "Saved and frequent", savedName = "Home", tripCount = 50),
            Destination(5, 0.0, 0.0, "Twice", tripCount = 2, lastTripAtMillis = 3),
        )
        assertEquals(listOf("Often", "Twice", "Once, lately"), places.recents().map { it.placeName })
        assertEquals(listOf("Home"), places.saved().map { it.savedName })
    }

    @Test fun a_place_without_a_name_is_called_by_its_address() = runBlocking {
        repository.recordTrip(NavigationDestination(13.0, 80.0, DestinationRepository.GenericTitle))
        repository.recordTrip(NavigationDestination(14.0, 80.0, "13.0827, 80.2707"))
        repository.recordTrip(NavigationDestination(15.0, 80.0, "Phoenix Marketcity"))

        assertEquals(listOf("12 Anna Salai", "12 Anna Salai", "Phoenix Marketcity"), all().sortedBy { it.latitude }.map { it.placeName })
        assertEquals(2, addressLookups)
    }

    @Test fun saving_a_recent_place_keeps_its_trips_and_renaming_changes_only_the_name() = runBlocking {
        repository.recordTrip(NavigationDestination(13.0, 80.0, "Office park"))
        repository.save(NavigationDestination(13.0001, 80.0, "Office park"), "  Work  ")

        val work = all().single()
        assertEquals("Work", work.savedName)
        assertEquals(1, work.tripCount)
        repository.rename(work.id, "Office")
        assertEquals("Office", all().single().savedName)
        assertEquals("Office park", all().single().placeName)
    }

    @Test fun clearing_recents_forgets_trips_but_keeps_saved_places() = runBlocking {
        repository.recordTrip(NavigationDestination(13.0, 80.0, "Recent"))
        repository.save(NavigationDestination(14.0, 80.0, "Saved"), "Home")
        repository.recordTrip(NavigationDestination(14.0, 80.0, "Saved"))

        repository.clearRecents()

        val home = all().single()
        assertEquals("Home", home.savedName)
        assertEquals(0, home.tripCount)
        assertNull(home.lastTripAtMillis)
    }

    @Test fun only_the_most_relevant_recents_are_kept() = runBlocking {
        repeat(DestinationRepository.MaxRecents + 5) { index ->
            clock += 1
            repository.recordTrip(NavigationDestination(10.0 + index, 80.0, "Place $index"))
        }
        repository.save(NavigationDestination(50.0, 80.0, "Saved"), "Never pruned")

        val kept = all()
        assertEquals(DestinationRepository.MaxRecents + 1, kept.size)
        assertTrue(kept.any { it.savedName == "Never pruned" })
        // Ties on one trip each go to the most recent.
        assertTrue(kept.none { it.placeName == "Place 0" })
    }

    @Test fun the_saved_place_at_a_point_is_found_within_the_same_place_radius() {
        val places = listOf(Destination(1, 13.0, 80.0, "Home street", savedName = "Home"))
        assertEquals("Home", places.savedAt(13.0002, 80.0)?.savedName)
        assertNull(places.savedAt(13.01, 80.0))
    }
}
