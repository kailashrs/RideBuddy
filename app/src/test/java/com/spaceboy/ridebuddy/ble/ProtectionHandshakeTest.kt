package com.spaceboy.ridebuddy.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProtectionHandshakeTest {
    @Test
    fun `returns the observed response for a known challenge`() {
        val challenge = hex("63 75 A3 A4 63 3B")

        assertArrayEquals(hex("E9 77 97 5C C3 45"), ProtectionHandshake.responseFor(challenge))
    }

    @Test
    fun `does not guess a response for an unknown challenge`() {
        assertNull(ProtectionHandshake.responseFor(ByteArray(6)))
    }

    private fun hex(value: String): ByteArray = value.split(" ")
        .map { part -> part.toInt(16).toByte() }
        .toByteArray()
}
