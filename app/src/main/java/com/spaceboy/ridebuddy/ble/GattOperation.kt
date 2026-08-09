package com.spaceboy.ridebuddy.ble

import android.bluetooth.BluetoothGattCharacteristic
import com.spaceboy.ridebuddy.domain.BikeWriteMode
import kotlinx.coroutines.CompletableDeferred

internal sealed interface GattOperation {
    val label: String
    val attempt: Int
    val awaitsCallback: Boolean get() = true
    fun retry(): GattOperation

    data class Subscribe(val characteristic: BluetoothGattCharacteristic, override val attempt: Int = 0) :
        GattOperation {
        override val label = "subscription"
        override fun retry() = copy(attempt = attempt + 1)
    }

    data class Read(val characteristic: BluetoothGattCharacteristic, override val attempt: Int = 0) :
        GattOperation {
        override val label = "read"
        override fun retry() = copy(attempt = attempt + 1)
    }

    data class Write(
        val characteristic: BluetoothGattCharacteristic,
        val value: ByteArray,
        val mode: BikeWriteMode = BikeWriteMode.Default,
        val completion: CompletableDeferred<Boolean>? = null,
        val requestId: Long? = null,
        override val attempt: Int = 0,
    ) : GattOperation {
        override val label = "write"
        override val awaitsCallback: Boolean
            get() = requestedWriteType(characteristic, mode) == BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT

        override fun retry() = copy(attempt = attempt + 1)
    }
}
