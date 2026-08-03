package com.spaceboy.ridebuddy.core.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class NavigationApiKeyPolicyTest {
    @Test
    fun `accepts a plausible key without exposing it`() {
        val key = "AIzaSyExampleKeyValue1234567890"

        assertNull(NavigationApiKeyPolicy.validate(key))
        assertEquals("•••• 7890", NavigationApiKeyPolicy.mask(key))
    }

    @Test
    fun `rejects empty short and whitespace values`() {
        assertNotNull(NavigationApiKeyPolicy.validate(""))
        assertNotNull(NavigationApiKeyPolicy.validate("short"))
        assertNotNull(NavigationApiKeyPolicy.validate("AIzaSyExampleKey Value1234567890"))
    }
}
