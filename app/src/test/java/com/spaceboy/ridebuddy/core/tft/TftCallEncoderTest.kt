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
        assertArrayEquals(byteArrayOf(1, 0, 0, 1), TftCallEncoder.ringing())
        assertArrayEquals(byteArrayOf(1, 1, 0, 0), TftCallEncoder.accepted())
        assertArrayEquals(byteArrayOf(1, 0, 1, 0), TftCallEncoder.ended())
    }

    @Test
    fun callerTextIsNormalizedToProtocolSafeAscii() {
        val name = TftCallEncoder.callerName("José 🚀")
        val number = TftCallEncoder.callerNumber("+١٢ 34")

        assertEquals("Jose", name.copyOfRange(1, 5).toString(Charsets.US_ASCII))
        assertEquals("+34", number.copyOfRange(0, 3).toString(Charsets.US_ASCII))
    }
}
