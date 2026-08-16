package com.spaceboy.ridebuddy.ble

import android.bluetooth.BluetoothGattCharacteristic
import com.spaceboy.ridebuddy.domain.BikeWriteMode
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class GattOperationTest {
    @Test
    fun requestedWriteModePrefersTheRequestedCapabilityAndFallsBackSafely() {
        val both = BluetoothGattCharacteristic.PROPERTY_WRITE or
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE

        assertEquals(
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
            requestedWriteType(both, BikeWriteMode.Default),
        )
        assertEquals(
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
            requestedWriteType(both, BikeWriteMode.NoResponsePreferred),
        )
        assertEquals(
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
            requestedWriteType(
                BluetoothGattCharacteristic.PROPERTY_WRITE,
                BikeWriteMode.NoResponsePreferred,
            ),
        )
        assertNull(requestedWriteType(BluetoothGattCharacteristic.PROPERTY_READ, BikeWriteMode.Default))
    }

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

    @Test
    fun readRetryIncrementsAttempt() {
        val characteristic = BluetoothGattCharacteristic(
            UUID.randomUUID(),
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )

        val retry = GattOperation.Read(characteristic).retry()

        assertEquals(1, retry.attempt)
    }

    @Test
    fun retryRetainsOperationPriority() {
        val characteristic = BluetoothGattCharacteristic(
            UUID.randomUUID(),
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )

        val retry = GattOperation.Write(
            characteristic,
            byteArrayOf(0x01),
            priority = GattOperationPriority.Critical,
        ).retry()

        assertEquals(GattOperationPriority.Critical, retry.priority)
    }

    @Test
    fun retryRetainsNoResponseWriteMode() {
        val characteristic = BluetoothGattCharacteristic(
            UUID.randomUUID(),
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )

        val retry = GattOperation.Write(
            characteristic = characteristic,
            value = byteArrayOf(0x01),
            mode = BikeWriteMode.NoResponsePreferred,
        ).retry()

        assertEquals(BikeWriteMode.NoResponsePreferred, retry.mode)
        assertEquals(1, retry.attempt)
    }

    @Test
    fun acceptedNoResponseWriteWaitsForItsFrameworkCallback() {
        val characteristic = BluetoothGattCharacteristic(
            UUID.randomUUID(),
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        val operation = GattOperation.Write(
            characteristic = characteristic,
            value = byteArrayOf(0x01),
            mode = BikeWriteMode.NoResponsePreferred,
        )

        assertEquals(BikeWriteMode.NoResponsePreferred, operation.mode)
        assertEquals(GattStartAction.AwaitCallback, gattStartAction(started = true))
    }

    @Test
    fun acceptedOperationTimeoutResetsGattInsteadOfRetryingCurrentSession() {
        assertEquals(
            GattFailureAction.ResetGattAndReconnect,
            gattFailureAction(
                source = GattFailureSource.CallbackTimeout,
                attempt = 0,
                maxRetries = 2,
            ),
        )
    }

    @Test
    fun synchronousAndStatusFailuresUseTheSameBoundedRetryPolicy() {
        listOf(GattFailureSource.SynchronousStart, GattFailureSource.StatusCallback).forEach { source ->
            assertEquals(GattFailureAction.RetryCurrentGatt, gattFailureAction(source, 0, maxRetries = 2))
            assertEquals(GattFailureAction.RetryCurrentGatt, gattFailureAction(source, 1, maxRetries = 2))
            assertEquals(GattFailureAction.CompleteFailure, gattFailureAction(source, 2, maxRetries = 2))
        }
        assertEquals(GattStartAction.HandleSynchronousFailure, gattStartAction(started = false))
    }
}
