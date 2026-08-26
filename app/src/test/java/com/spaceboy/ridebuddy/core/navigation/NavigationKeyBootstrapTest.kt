package com.spaceboy.ridebuddy.core.navigation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationKeyBootstrapTest {
    @Test
    fun `shared bootstrap loads and configures a stored key only once`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var loads = 0
        var configurations = 0
        val bootstrap = NavigationKeyBootstrap(
            scope = scope,
            dispatcher = Dispatchers.Unconfined,
            loadKey = {
                loads++
                "AIzaSyExampleKeyValue1234567890"
            },
            configureKey = {
                configurations++
                ConfigureResult.Configured
            },
        )

        val first = bootstrap.await().getOrThrow()
        val second = bootstrap.await().getOrThrow()

        assertEquals(1, loads)
        assertEquals(1, configurations)
        assertEquals("•••• 7890", first.maskedKey)
        assertTrue(first.isConfigured)
        assertEquals(first, second)
        scope.cancel()
    }

    @Test
    fun `saved and removed keys replace the process snapshot for later consumers`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val bootstrap = NavigationKeyBootstrap(
            scope = scope,
            dispatcher = Dispatchers.Unconfined,
            loadKey = { "AIzaSyOriginalKeyValue1234567890" },
            configureKey = { ConfigureResult.Configured },
        )
        bootstrap.await().getOrThrow()

        bootstrap.recordSavedKey(
            "AIzaSyReplacementKeyValue1234567899",
            ConfigureResult.RestartRequired,
        )
        val replacement = bootstrap.await().getOrThrow()
        assertEquals("•••• 7899", replacement.maskedKey)
        assertTrue(replacement.restartRequired)

        bootstrap.recordRemovedKey(restartRequired = true)
        val removed = bootstrap.await().getOrThrow()
        assertEquals(null, removed.maskedKey)
        assertFalse(removed.isConfigured)
        assertTrue(removed.restartRequired)
        scope.cancel()
    }

    @Test
    fun `bootstrap reports load failure without retaining a key`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val bootstrap = NavigationKeyBootstrap(
            scope = scope,
            dispatcher = Dispatchers.Unconfined,
            loadKey = { error("Keystore unavailable") },
            configureKey = { ConfigureResult.Configured },
        )

        val result = bootstrap.await()

        assertTrue(result.isFailure)
        assertFalse(result.exceptionOrNull()?.message.isNullOrBlank())
        scope.cancel()
    }
}
