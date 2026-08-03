package com.spaceboy.ridebuddy

import com.spaceboy.ridebuddy.core.navigation.ConfigureResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainViewModelNavigationStateTest {
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
}
