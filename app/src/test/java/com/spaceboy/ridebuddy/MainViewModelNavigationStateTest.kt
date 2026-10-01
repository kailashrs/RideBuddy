package com.spaceboy.ridebuddy

import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class MainViewModelNavigationStateTest {
    @Test
    fun `a share selects Live, closes child screens and replaces an earlier failure`() {
        val state = MainUiState(
            selectedDestination = TopLevelDestination.Settings,
            isNavigationSettingsOpen = true,
            isDiagnosticsOpen = true,
            failedShare = "Old link",
            isNavigationStarting = true,
            navigationStartAttemptId = 9L,
        )
        val share = PendingShare(3L, "https://maps.app.goo.gl/x")

        val result = state.withPendingShare(share)

        assertEquals(TopLevelDestination.Live, result.selectedDestination)
        assertFalse(result.isNavigationSettingsOpen)
        assertFalse(result.isDiagnosticsOpen)
        assertEquals(share, result.pendingShare)
        assertNull(result.failedShare)
        assertFalse(result.isNavigationStarting)
        assertNull(result.navigationStartAttemptId)
    }

    @Test
    fun `completing a share clears it, but only the share it names`() {
        val state = MainUiState(pendingShare = PendingShare(5L, "Current"), isNavigationStarting = true, navigationStartAttemptId = 6L)

        assertSame(state, state.withCompletedShare(4L))
        val completed = state.withCompletedShare(5L)
        assertNull(completed.pendingShare)
        assertFalse(completed.isNavigationStarting)
        assertNull(completed.navigationStartAttemptId)
    }

    @Test
    fun `a failed share is kept for Retry with its reason as the message`() {
        val state = MainUiState(pendingShare = PendingShare(7L, "Unreadable link"))

        assertSame(state, state.withFailedShare(6L, "Stale"))
        val failed = state.withFailedShare(7L, "Could not read that destination")
        assertNull(failed.pendingShare)
        assertEquals("Unreadable link", failed.failedShare)
        assertEquals("Could not read that destination", failed.transientMessage)
    }

    @Test
    fun `stale navigation start attempts cannot clear a newer attempt`() {
        val firstAttempt = MainUiState().withNavigationStartAttempt(21L)
        val secondAttempt = firstAttempt.withNavigationStartAttempt(22L)

        val staleFinish = secondAttempt.withFinishedNavigationStartAttempt(21L)
        val currentFinish = staleFinish.withFinishedNavigationStartAttempt(22L)

        assertSame(secondAttempt, staleFinish)
        assertFalse(currentFinish.isNavigationStarting)
        assertNull(currentFinish.navigationStartAttemptId)
    }

    @Test
    fun `a pending share survives saved state restoration`() {
        val savedStateHandle = SavedStateHandle()
        val share = PendingShare(31L, "Saved destination")

        SharedDestinationStateStore(savedStateHandle).persist(
            MainUiState(pendingShare = share, isNavigationStarting = true, navigationStartAttemptId = 32L),
        )
        val restored = SharedDestinationStateStore(savedStateHandle).restore()

        assertEquals(share, restored.pendingShare)
        assertFalse(restored.isNavigationStarting)
        assertNull(restored.navigationStartAttemptId)
    }

    @Test
    fun `a handled share is gone after restoration`() {
        val savedStateHandle = SavedStateHandle()
        val store = SharedDestinationStateStore(savedStateHandle)
        store.persist(MainUiState(pendingShare = PendingShare(41L, "Old destination")))

        store.persist(MainUiState())

        assertNull(SharedDestinationStateStore(savedStateHandle).restore().pendingShare)
    }
}
