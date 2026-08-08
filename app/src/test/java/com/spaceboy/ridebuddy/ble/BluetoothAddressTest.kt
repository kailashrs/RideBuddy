package com.spaceboy.ridebuddy.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BluetoothAddressTest {
    @Test
    fun parseAcceptsPlatformTextRepresentations() {
        val expected = requireNotNull(BluetoothAddress.parse("CC:B3:1E:C1:E1:B7"))

        assertEquals(expected, BluetoothAddress.parse("cc:b3:1e:c1:e1:b7"))
        assertEquals(expected, BluetoothAddress.parse("  Cc:B3:1e:c1:E1:b7  "))
        assertEquals("CC:B3:1E:C1:E1:B7", expected.toString())
    }

    @Test
    fun bytesRoundTripWithoutUsingAddressTextForResolution() {
        val bytes = byteArrayOf(0xCC.toByte(), 0xB3.toByte(), 0x1E, 0xC1.toByte(), 0xE1.toByte(), 0xB7.toByte())
        val address = BluetoothAddress.fromBytes(bytes)

        assertArrayEquals(bytes, address?.toByteArray())
        assertEquals(address, BluetoothAddress.parse(address.toString()))
    }

    @Test
    fun malformedValuesAreRejected() {
        listOf(
            null,
            "",
            "CC:B3:1E:C1:E1",
            "CC-B3-1E-C1-E1-B7",
            "GG:B3:1E:C1:E1:B7",
            "CC:B3:1E:C1:E1:B7:00",
        ).forEach { assertNull(BluetoothAddress.parse(it)) }
        assertNull(BluetoothAddress.fromBytes(byteArrayOf(1, 2, 3, 4, 5)))
    }

    @Test
    fun addressValueHasStableEquality() {
        val first = BluetoothAddress.parse("CC:B3:1E:C1:E1:B7")
        val same = BluetoothAddress.fromBytes(first?.toByteArray())
        val different = BluetoothAddress.parse("CC:B3:1E:C1:E1:B8")

        assertEquals(first, same)
        assertEquals(first.hashCode(), same.hashCode())
        assertNotEquals(first, different)
    }
}
