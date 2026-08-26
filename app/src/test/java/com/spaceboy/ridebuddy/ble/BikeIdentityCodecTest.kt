package com.spaceboy.ridebuddy.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BikeIdentityCodecTest {
    @Test
    fun `decodes raw and OEM-framed VIN values`() {
        val vin = "MABCDE12XF1234567"

        assertEquals(vin, vin.encodeToByteArray().decodeBikeVin())
        assertEquals(
            vin,
            (byteArrayOf(0x02) + vin.encodeToByteArray() + byteArrayOf(0x03)).decodeBikeVin(),
        )
    }

    @Test
    fun `rejects malformed VIN values`() {
        assertNull("too short".encodeToByteArray().decodeBikeVin())
        assertNull(ByteArray(17) { if (it == 8) 0x00 else 0x41 }.decodeBikeVin())
    }

    @Test
    fun `trims cluster software padding`() {
        assertEquals(
            "cluster-1.2.3",
            "  cluster-1.2.3\u0000\r\n".encodeToByteArray().decodeClusterSoftwareVersion(),
        )
    }
}
