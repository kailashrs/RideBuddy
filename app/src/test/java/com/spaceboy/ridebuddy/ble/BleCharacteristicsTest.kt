package com.spaceboy.ridebuddy.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BleCharacteristicsTest {
    @Test
    fun `RideBuddy post-authentication subscription order is deterministic`() {
        assertEquals(
            listOf("8280", "8720", "8740", "8410"),
            BleCharacteristics.PostAuthenticationSubscriptions.map { it.toString().takeLast(4) },
        )
    }

    @Test
    fun `post-authentication subscriptions match the notifying subset only`() {
        assertEquals(
            setOf("8280", "8720", "8740", "8410"),
            BleCharacteristics.PostAuthenticationSubscriptions.mapTo(mutableSetOf()) {
                it.toString().takeLast(4)
            },
        )
    }

    @Test
    fun `read-only identity characteristics are queued separately`() {
        assertEquals(
            listOf("8810", "8910"),
            BleCharacteristics.PostAuthenticationIdentityReads.map { it.toString().takeLast(4) },
        )
        BleCharacteristics.PostAuthenticationSubscriptions.forEach { uuid ->
            assertFalse(uuid in BleCharacteristics.PostAuthenticationIdentityReads)
        }
    }

    @Test
    fun `SR-only mobile status is not part of RS post-authentication flow`() {
        assertFalse(BleCharacteristics.SrMobileStatus in BleCharacteristics.PostAuthenticationSubscriptions)
        assertFalse(BleCharacteristics.SrMobileStatus in BleCharacteristics.PostAuthenticationIdentityReads)
    }
}
