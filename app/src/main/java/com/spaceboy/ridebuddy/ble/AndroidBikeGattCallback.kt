package com.spaceboy.ridebuddy.ble

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor

/** Adapts Android's callback surface to connection-controller events and owns payload copying. */
internal class AndroidBikeGattCallback(
    private val handleConnectionStateChanged: (BluetoothGatt, Int, Int) -> Unit,
    private val handleMtuChanged: (BluetoothGatt, Int, Int) -> Unit,
    private val handleServicesDiscovered: (BluetoothGatt, Int) -> Unit,
    private val handleNotification: (BluetoothGatt, BluetoothGattCharacteristic, ByteArray) -> Unit,
    private val handleRead: (BluetoothGatt, BluetoothGattCharacteristic, ByteArray, Int) -> Unit,
    private val handleDescriptorWrite: (BluetoothGatt, BluetoothGattDescriptor, Int) -> Unit,
    private val handleWrite: (BluetoothGatt, BluetoothGattCharacteristic, Int) -> Unit,
    private val handleRssiRead: (BluetoothGatt, Int, Int) -> Unit,
) : BluetoothGattCallback() {
    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) =
        handleConnectionStateChanged(gatt, status, newState)

    override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) =
        handleMtuChanged(gatt, mtu, status)

    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) =
        handleServicesDiscovered(gatt, status)

    override fun onCharacteristicChanged(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
    ) = handleNotification(gatt, characteristic, value.copyOf())

    override fun onCharacteristicRead(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        status: Int,
    ) = handleRead(gatt, characteristic, value.copyOf(), status)

    override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) =
        handleDescriptorWrite(gatt, descriptor, status)

    override fun onCharacteristicWrite(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        status: Int,
    ) = handleWrite(gatt, characteristic, status)

    override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) =
        handleRssiRead(gatt, rssi, status)
}
