package com.spaceboy.ridebuddy.domain

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class BikeWriteTest {
    @Test
    fun byteArrayPayloadsUseStructuralEquality() {
        val characteristic = UUID.randomUUID()
        val expected = BikeWrite(characteristic, byteArrayOf(1, 2, 3))
        val equalValue = BikeWrite(characteristic, byteArrayOf(1, 2, 3))
        val differentValue = BikeWrite(characteristic, byteArrayOf(1, 2, 4))
        val differentMode = BikeWrite(
            characteristic,
            byteArrayOf(1, 2, 3),
            BikeWriteMode.NoResponsePreferred,
        )

        assertEquals(expected, equalValue)
        assertEquals(expected.hashCode(), equalValue.hashCode())
        assertNotEquals(expected, differentValue)
        assertNotEquals(expected, differentMode)
    }
}
