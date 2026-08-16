package com.spaceboy.ridebuddy.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothStatusCodes
import android.os.Build
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
    fun start(gatt: BluetoothGatt, operation: GattOperation): Boolean = when (operation) {
        is GattOperation.Subscribe -> subscribe(gatt, operation.characteristic)
        is GattOperation.Read -> gatt.readCharacteristic(operation.characteristic)
        is GattOperation.Write -> writeCharacteristic(
            gatt,
            operation.characteristic,
            operation.value,
            operation.mode,
        )
    }

    private fun subscribe(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic): Boolean {
        if (!gatt.setCharacteristicNotification(characteristic, true)) return false
        val descriptor =
            characteristic.getDescriptor(BleCharacteristics.ClientCharacteristicConfiguration) ?: return false
        val indication = characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
        val value = if (indication) {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                descriptor.value = value
                gatt.writeDescriptor(descriptor)
            }
        }
    }

    private fun writeCharacteristic(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        mode: BikeWriteMode,
    ): Boolean {
        val writeType = requestedWriteType(characteristic, mode) ?: return false
        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(characteristic, value, writeType) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                characteristic.writeType = writeType
                characteristic.value = value
                gatt.writeCharacteristic(characteristic)
            }
        }
        captureRecorder.record(
            BleCaptureDirection.Outbound,
            characteristic.uuid,
            value,
            if (started) "accepted" else "rejected",
        )
        return started
    }
}
