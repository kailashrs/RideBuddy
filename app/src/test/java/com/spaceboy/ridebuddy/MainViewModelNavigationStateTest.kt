package com.spaceboy.ridebuddy

import androidx.lifecycle.SavedStateHandle
import com.spaceboy.ridebuddy.core.navigation.ConfigureResult
import com.spaceboy.ridebuddy.core.navigation.NavigationKeyBootstrapResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MainViewModelNavigationStateTest {
    @Test
    fun `manual shared destination selects Live and replaces an automatic request`() {
        val state = MainUiState(
            selectedDestination = TopLevelDestination.More,
            isNavigationSettingsOpen = true,
            isDiagnosticsOpen = true,
            sharedDestinationError = "Old error",
            autoStartSharedDestination = AutoStartSharedDestinationRequest(1L, "Old destination"),
            isNavigationStarting = true,
            navigationStartAttemptId = 11L,
        )

        val result = state.withManualSharedDestination("Manual destination")

        assertEquals(TopLevelDestination.Live, result.selectedDestination)
        assertFalse(result.isNavigationSettingsOpen)
        assertFalse(result.isDiagnosticsOpen)
        assertEquals("Manual destination", result.sharedDestination)
        assertNull(result.sharedDestinationError)
        assertNull(result.autoStartSharedDestination)
        assertFalse(result.isNavigationStarting)
        assertNull(result.navigationStartAttemptId)
    }

    @Test
    fun `automatic shared destination selects Live and replaces a manual request`() {
        val request = AutoStartSharedDestinationRequest(2L, "Automatic destination")
        val state = MainUiState(
            selectedDestination = TopLevelDestination.History,
            isNavigationSettingsOpen = true,
            isDiagnosticsOpen = true,
            sharedDestination = "Old manual destination",
            sharedDestinationError = "Old error",
            isNavigationStarting = true,
            navigationStartAttemptId = 12L,
        )

        val result = state.withAutoStartSharedDestination(request)

        assertEquals(TopLevelDestination.Live, result.selectedDestination)
        assertFalse(result.isNavigationSettingsOpen)
        assertFalse(result.isDiagnosticsOpen)
        assertNull(result.sharedDestination)
        assertNull(result.sharedDestinationError)
        assertEquals(request, result.autoStartSharedDestination)
        assertFalse(result.isNavigationStarting)
        assertNull(result.navigationStartAttemptId)
    }

    @Test
    fun `successful automatic start clears the matching request`() {
        val state = MainUiState(
            autoStartSharedDestination = AutoStartSharedDestinationRequest(3L, "Destination"),
            isNavigationStarting = true,
            navigationStartAttemptId = 13L,
        )

        val result = state.withCompletedAutoStartSharedDestination(3L)

        assertNull(result.autoStartSharedDestination)
        assertNull(result.sharedDestination)
        assertFalse(result.isNavigationStarting)
        assertNull(result.navigationStartAttemptId)
    }

    @Test
    fun `failed automatic start restores the matching request for manual confirmation`() {
        val state = MainUiState(
            selectedDestination = TopLevelDestination.More,
            isNavigationSettingsOpen = true,
            isDiagnosticsOpen = true,
            autoStartSharedDestination = AutoStartSharedDestinationRequest(4L, "Retry destination"),
            isNavigationStarting = true,
            navigationStartAttemptId = 14L,
        )

        val result = state.withRestoredAutoStartSharedDestination(4L, "Could not resolve it")

        assertEquals(TopLevelDestination.Live, result.selectedDestination)
        assertFalse(result.isNavigationSettingsOpen)
        assertFalse(result.isDiagnosticsOpen)
        assertEquals("Retry destination", result.sharedDestination)
        assertEquals("Could not resolve it", result.sharedDestinationError)
        assertNull(result.autoStartSharedDestination)
        assertFalse(result.isNavigationStarting)
        assertNull(result.navigationStartAttemptId)
    }

    @Test
    fun `missing configuration restores the automatic destination without a parse error`() {
        val state = MainUiState(
            autoStartSharedDestination = AutoStartSharedDestinationRequest(5L, "Configure then retry"),
            isNavigationStarting = true,
            navigationStartAttemptId = 15L,
        )

        val result = state.withRestoredAutoStartSharedDestination(5L)

        assertEquals("Configure then retry", result.sharedDestination)
        assertNull(result.sharedDestinationError)
        assertNull(result.autoStartSharedDestination)
        assertFalse(result.isNavigationStarting)
        assertNull(result.navigationStartAttemptId)
    }

    @Test
    fun `stale completion cannot clear a newer automatic request`() {
        val state = MainUiState(
            autoStartSharedDestination = AutoStartSharedDestinationRequest(6L, "New destination"),
        )

        val result = state.withCompletedAutoStartSharedDestination(5L)

        assertSame(state, result)
    }

    @Test
    fun `stale failure cannot restore an older automatic request`() {
        val state = MainUiState(
            autoStartSharedDestination = AutoStartSharedDestinationRequest(8L, "New destination"),
        )

        val result = state.withRestoredAutoStartSharedDestination(7L)

        assertSame(state, result)
    }

    @Test
    fun `only the owning navigation attempt can clear loading`() {
        val firstAttempt = MainUiState().withNavigationStartAttempt(21L)
        val secondAttempt = firstAttempt.withNavigationStartAttempt(22L)

        val staleFinish = secondAttempt.withFinishedNavigationStartAttempt(21L)
        val currentFinish = staleFinish.withFinishedNavigationStartAttempt(22L)

        assertSame(secondAttempt, staleFinish)
        assertFalse(currentFinish.isNavigationStarting)
        assertNull(currentFinish.navigationStartAttemptId)
    }

    @Test
    fun `pending automatic destination survives saved state restoration`() {
        val savedStateHandle = SavedStateHandle()
        val store = SharedDestinationStateStore(savedStateHandle)
        val request = AutoStartSharedDestinationRequest(31L, "Saved destination")

        store.persist(
            MainUiState(
                autoStartSharedDestination = request,
                isNavigationStarting = true,
                navigationStartAttemptId = 32L,
            ),
        )

        val restored = SharedDestinationStateStore(savedStateHandle).restore()

        assertEquals(request, restored.autoStartSharedDestination)
        assertNull(restored.sharedDestination)
        assertFalse(restored.isNavigationStarting)
        assertNull(restored.navigationStartAttemptId)
    }

    @Test
    fun `failed automatic destination restores as an editable saved draft`() {
        val savedStateHandle = SavedStateHandle()
        val store = SharedDestinationStateStore(savedStateHandle)

        store.persist(
            MainUiState(
                sharedDestination = "Retry destination",
                sharedDestinationError = "Could not resolve it",
            ),
        )

        val restored = SharedDestinationStateStore(savedStateHandle).restore()

        assertEquals("Retry destination", restored.sharedDestination)
        assertEquals("Could not resolve it", restored.sharedDestinationError)
        assertNull(restored.autoStartSharedDestination)
    }

    @Test
    fun `terminal destination state removes the saved request`() {
        val savedStateHandle = SavedStateHandle()
        val store = SharedDestinationStateStore(savedStateHandle)
        store.persist(
            MainUiState(
                autoStartSharedDestination = AutoStartSharedDestinationRequest(41L, "Old destination"),
            ),
        )

        store.persist(MainUiState())

        val restored = SharedDestinationStateStore(savedStateHandle).restore()
        assertNull(restored.autoStartSharedDestination)
        assertNull(restored.sharedDestination)
        assertNull(restored.sharedDestinationError)
    }

    @Test
    fun `failed SDK configuration does not make navigation available`() {
        val current = NavigationKeyUiState(isConfigured = false, isSaving = true)

        val result = navigationKeyStateFor(
            ConfigureResult.Failed("Navigation SDK rejected the API key"),
            "AIzaSyExampleKeyValue1234567890",
            current,
        )

        assertFalse(result.isConfigured)
        assertFalse(result.isSaving)
        assertEquals("Navigation SDK rejected the API key", result.errorMessage)
    }

    @Test
    fun `successful SDK configuration exposes only a masked key`() {
        val result = navigationKeyStateFor(
            ConfigureResult.Configured,
            "AIzaSyExampleKeyValue1234567890",
            NavigationKeyUiState(isSaving = true),
        )

        assertTrue(result.isConfigured)
        assertFalse(result.isSaving)
        assertEquals("•••• 7890", result.maskedKey)
    }

    @Test
    fun `replacement key requires restart but remains configured for next launch`() {
        val result = navigationKeyStateFor(
            ConfigureResult.RestartRequired,
            "AIzaSyReplacementKeyValue1234567890",
            NavigationKeyUiState(isConfigured = true, maskedKey = "•••• 7890", isSaving = true),
        )

        assertTrue(result.isConfigured)
        assertTrue(result.restartRequired)
    }

    @Test
    fun `successful process bootstrap exposes the stored key only in masked form`() {
        val state = navigationKeyStateForBootstrap(
            Result.success(
                NavigationKeyBootstrapResult(
                    maskedKey = "•••• 7890",
                    isConfigured = true,
                ),
            ),
        )

        assertTrue(state.isConfigured)
        assertFalse(state.isLoading)
        assertEquals("•••• 7890", state.maskedKey)
    }

    @Test
    fun `failed process bootstrap keeps navigation unavailable with a useful error`() {
        val state = navigationKeyStateForBootstrap(
            Result.success(
                NavigationKeyBootstrapResult(
                    maskedKey = "•••• 7890",
                    isConfigured = false,
                    errorMessage = "SDK configuration failed",
                ),
            ),
        )

        assertFalse(state.isConfigured)
        assertEquals("SDK configuration failed", state.errorMessage)
    }
}
