package com.spaceboy.ridebuddy.core.security

import org.junit.Assert.assertThrows
import org.junit.Test

class SecureNavigationApiKeyStoreTest {
    @Test
    fun failedPreferenceCommitIsSurfaced() {
        assertThrows(IllegalStateException::class.java) {
            requirePreferenceCommit(false, "save the navigation API key")
        }
    }

    @Test
    fun successfulPreferenceCommitReturnsNormally() {
        requirePreferenceCommit(true, "save the navigation API key")
    }
}
