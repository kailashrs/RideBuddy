package com.spaceboy.ridebuddy.ble

import android.bluetooth.BluetoothGattCharacteristic
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Test

class GattOperationTest {
    @Test
    fun writeEqualityUsesPayloadReferenceSemantics() {
        val characteristic = BluetoothGattCharacteristic(
            UUID.randomUUID(),
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        val payload = byteArrayOf(0x01, 0x02)
        val write = GattOperation.Write(characteristic, payload)

        assertEquals(write, GattOperation.Write(characteristic, payload))
        assertNotEquals(write, GattOperation.Write(characteristic, payload.copyOf()))
    }

    @Test
    fun retryRetainsPayloadAndIncrementsAttempt() {
        val characteristic = BluetoothGattCharacteristic(
            UUID.randomUUID(),
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        val payload = byteArrayOf(0x01, 0x02)

        val retry = GattOperation.Write(characteristic, payload).retry()

        assertSame(payload, retry.value)
        assertEquals(1, retry.attempt)
    }
}
