package com.spaceboy.ridebuddy.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BleCharacteristicsTest {
    @Test
    fun `RideBuddy post-authentication subscription order is deterministic`() {
        assertEquals(
            listOf("8280", "8720", "8740", "8410", "8810", "8910"),
            BleCharacteristics.PostAuthenticationSubscriptions.map { it.toString().takeLast(4) },
        )
    }

    @Test
    fun `post-authentication subscriptions match the India OEM profile`() {
        assertEquals(
            setOf("8280", "8720", "8740", "8410", "8810", "8910"),
            BleCharacteristics.PostAuthenticationSubscriptions.mapTo(mutableSetOf()) {
                it.toString().takeLast(4)
            },
        )
    }

    @Test
    fun `SR-only mobile status is not part of RS post-authentication flow`() {
        assertFalse(BleCharacteristics.SrMobileStatus in BleCharacteristics.PostAuthenticationSubscriptions)
    }

    @Test
    fun `identity snapshots preserve the OEM subscription profile`() {
        assertEquals(
            listOf("8810", "8910"),
            BleCharacteristics.PostAuthenticationIdentityReads.map { it.toString().takeLast(4) },
        )
        assertTrue(
            BleCharacteristics.PostAuthenticationSubscriptions.containsAll(
                BleCharacteristics.PostAuthenticationIdentityReads,
            ),
        )
    }
}
