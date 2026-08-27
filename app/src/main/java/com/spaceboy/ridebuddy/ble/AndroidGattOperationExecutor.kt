package com.spaceboy.ridebuddy.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothStatusCodes
import com.spaceboy.ridebuddy.domain.BikeWriteMode

internal fun requestedWriteType(
    characteristic: BluetoothGattCharacteristic,
    mode: BikeWriteMode,
): Int? = requestedWriteType(characteristic.properties, mode)

internal fun requestedWriteType(properties: Int, mode: BikeWriteMode): Int? {
    val supportsDefault = properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0
    val supportsNoResponse =
        properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
    return when (mode) {
        BikeWriteMode.Default -> when {
            supportsDefault -> BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            supportsNoResponse -> BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            else -> null
        }

        BikeWriteMode.NoResponsePreferred -> when {
            supportsNoResponse -> BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            supportsDefault -> BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            else -> null
        }
    }
}

/** Performs one already-serialized Android GATT operation. */
@SuppressLint("MissingPermission")
internal class AndroidGattOperationExecutor(
    private val captureRecorder: BleCaptureRecorder,
) {
    /**
     * Starts [operation] and preserves the framework's reason for any synchronous refusal, so the
     * caller can tell a busy controller from an attribute that will never accept the operation.
     */
    fun start(gatt: BluetoothGatt, operation: GattOperation): GattStartOutcome = when (operation) {
        is GattOperation.Subscribe -> subscribe(gatt, operation.characteristic)
        is GattOperation.Read -> read(gatt, operation.characteristic)
        is GattOperation.Write -> writeCharacteristic(
            gatt,
            operation.characteristic,
            operation.value,
            operation.mode,
        )
    }

    private fun subscribe(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
    ): GattStartOutcome {
        // A refused local notification registration means the service cache or the link is gone.
        if (!gatt.setCharacteristicNotification(characteristic, true)) {
            return GattStartOutcome.Rejected(GattStartRejection.LinkUnusable)
        }
        val descriptor = characteristic.getDescriptor(BleCharacteristics.ClientCharacteristicConfiguration)
            ?: return GattStartOutcome.Rejected(GattStartRejection.NotPermitted)
        val indication = characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
        val value = if (indication) {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        }
        return outcomeFor(gatt.writeDescriptor(descriptor, value))
    }

    private fun read(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
    ): GattStartOutcome {
        // readCharacteristic() returns a bare boolean, so the deterministic cause is checked here
        // and anything else is treated as the transient "another operation is in flight" case.
        if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_READ == 0) {
            return GattStartOutcome.Rejected(GattStartRejection.NotPermitted)
        }
        return if (gatt.readCharacteristic(characteristic)) {
            GattStartOutcome.Started
        } else {
            GattStartOutcome.Rejected(GattStartRejection.Busy)
        }
    }

    private fun writeCharacteristic(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        mode: BikeWriteMode,
    ): GattStartOutcome {
        val writeType = requestedWriteType(characteristic, mode)
            ?: return GattStartOutcome.Rejected(GattStartRejection.NotPermitted)
        val outcome = outcomeFor(gatt.writeCharacteristic(characteristic, value, writeType))
        captureRecorder.record(
            BleCaptureDirection.Outbound,
            characteristic.uuid,
            value,
            if (outcome is GattStartOutcome.Started) "accepted" else "rejected",
        )
        return outcome
    }

    private fun outcomeFor(statusCode: Int): GattStartOutcome =
        if (statusCode == BluetoothStatusCodes.SUCCESS) {
            GattStartOutcome.Started
        } else {
            GattStartOutcome.Rejected(gattStartRejectionFor(statusCode), statusCode)
        }
}
