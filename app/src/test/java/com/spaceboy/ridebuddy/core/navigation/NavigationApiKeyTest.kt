package com.spaceboy.ridebuddy.core.navigation

import com.spaceboy.ridebuddy.core.security.NavigationApiKeyStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationApiKeyTest {
    private val key = "AIzaSyExampleKeyValue1234567890"

    @Test
    fun `validation rejects empty, short and whitespace keys and masks the rest`() {
        assertNull(NavigationApiKey.validationError(key))
        assertNotNull(NavigationApiKey.validationError(""))
        assertNotNull(NavigationApiKey.validationError("short"))
        assertNotNull(NavigationApiKey.validationError("AIzaSyExampleKey Value1234567890"))
        assertEquals("•••• 7890", NavigationApiKey.mask(key))
    }

    @Test
    fun `a key is applied once and a replacement waits for a restart`() = runBlocking {
        val applied = mutableListOf<String>()
        val apiKey = NavigationApiKey(store(), scope(), applyToSdk = { applied += it })
        apiKey.awaitLoaded()

        apiKey.save(key)
        assertTrue(apiKey.state.value.isConfigured)
        assertEquals("•••• 7890", apiKey.state.value.maskedKey)

        apiKey.save("AIzaSyReplacementKeyValue0000000000")
        assertTrue(apiKey.state.value.restartRequired)
        assertEquals(listOf(key), applied)
    }

    @Test
    fun `an SDK rejection leaves navigation unconfigured and the key unsaved`() = runBlocking {
        val store = store()
        val apiKey = NavigationApiKey(store, scope(), applyToSdk = { error("Navigation SDK rejected the API key") })
        apiKey.awaitLoaded()

        apiKey.save(key)

        assertFalse(apiKey.state.value.isConfigured)
        assertEquals("Navigation SDK rejected the API key", apiKey.state.value.errorMessage)
        assertNull(store.load())
    }

    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun store() = object : NavigationApiKeyStore {
        private var stored: String? = null
        override fun load() = stored
        override fun save(apiKey: String) { stored = apiKey }
        override fun clear() { stored = null }
    }
}
