package com.spaceboy.ridebuddy.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothStatusCodes
import com.spaceboy.ridebuddy.domain.BikeWriteMode

/**
 * The write type to use for [characteristic], or null when it accepts no write at all.
 *
 * [mode] expresses a preference, not a requirement: whichever type the characteristic
 * actually declares wins, and the preference only decides when it declares both. An
 * acknowledged write costs a round trip but reports its outcome; an unacknowledged one is
 * cheaper and is preferred for high-rate display updates where a dropped frame is
 * immediately superseded by the next.
 */
internal fun requestedWriteType(
    characteristic: BluetoothGattCharacteristic,
    mode: BikeWriteMode,
): Int? = requestedWriteType(characteristic.properties, mode)

/** Property-bitmask overload, so the choice can be exercised without a framework object. */
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

/**
 * Performs one already-serialized GATT operation against the framework.
 *
 * This class does no queueing and no retrying — [GattOperationScheduler] guarantees a
 * single operation is in flight, and [gattFailureAction] decides what a failure means.
 * All this does is translate an operation into the right framework call and translate the
 * refusal back into a reason the policy can act on.
 */
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
        // Indications are acknowledged and notifications are not, and writing the wrong
        // enable value leaves a characteristic silently unsubscribed. The characteristic's
        // own properties decide which it is, rather than a per-UUID table that would drift.
        val indication = characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
        val value = if (indication) {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        }
        return outcomeFor(gatt.writeDescriptor(descriptor, value))
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
