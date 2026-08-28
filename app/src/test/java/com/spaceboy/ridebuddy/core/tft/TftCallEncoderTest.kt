package com.spaceboy.ridebuddy.core.tft

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class TftCallEncoderTest {
    @Test
    fun callerNameUsesOemTwentyByteEnvelope() {
        val packet = TftCallEncoder.callerName("Kailash R S")

        assertEquals(20, packet.size)
        assertEquals(10, packet.first().toInt())
        assertEquals("Kailash R S", packet.copyOfRange(1, 12).toString(Charsets.US_ASCII))
    }

    @Test
    fun callStatesMatchOemFlags() {
        // [0x01, answered, ended, direction]
        assertArrayEquals(byteArrayOf(1, 0, 0, 1), TftCallEncoder.ringing())
        assertArrayEquals(byteArrayOf(1, 1, 0, 0), TftCallEncoder.accepted())
        assertArrayEquals(byteArrayOf(1, 0, 0, 2), TftCallEncoder.outgoing())
        assertArrayEquals(byteArrayOf(1, 0, 1, 0), TftCallEncoder.ended())
    }

    /**
     * The cluster field holds ten characters and the OEM writes `takeLast(10)`. Sending the whole
     * international number is what overflows it.
     */
    @Test
    fun callerNumberKeepsOnlyTheTrailingTenCharacters() {
        val packet = TftCallEncoder.callerNumber("+919876543210")

        assertEquals(20, packet.size)
        assertEquals("9876543210", packet.copyOfRange(0, 10).toString(Charsets.US_ASCII))
        // Nothing beyond the field length, so no stale digits from an earlier caller.
        assertEquals(0, packet[10].toInt())
    }

    @Test
    fun aShortNumberIsSentWholeAndZeroPadded() {
        val packet = TftCallEncoder.callerNumber("5551234")

        assertEquals("5551234", packet.copyOfRange(0, 7).toString(Charsets.US_ASCII))
        assertEquals(0, packet[7].toInt())
    }

    @Test
    fun callerTextIsNormalizedToProtocolSafeAscii() {
        val name = TftCallEncoder.callerName("José 🚀")
        val number = TftCallEncoder.callerNumber("+١٢ 34")

        assertEquals("Jose", name.copyOfRange(1, 5).toString(Charsets.US_ASCII))
        assertEquals("+34", number.copyOfRange(0, 3).toString(Charsets.US_ASCII))
    }
}
