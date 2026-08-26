package com.spaceboy.ridebuddy.ble

import android.bluetooth.BluetoothGatt
import com.spaceboy.ridebuddy.domain.BikeConnectionState

internal const val MaxReconnectAttempts = 6

private const val MaxReconnectDelayMillis = 30_000L

internal fun reconnectDelayMillis(attempt: Int): Long? {
    if (attempt !in 0 until MaxReconnectAttempts) return null
    return minOf(MaxReconnectDelayMillis, 1_000L shl minOf(attempt, 5))
}

internal fun shouldStartConnection(
    currentTarget: BikeConnectionTarget?,
    requestedTarget: BikeConnectionTarget,
    state: BikeConnectionState,
): Boolean = currentTarget?.address != requestedTarget.address ||
    state is BikeConnectionState.Disconnected ||
    state is BikeConnectionState.Failed

internal fun gattConnectionStatusLabel(status: Int): String = when (status) {
    BluetoothGatt.GATT_SUCCESS -> "success"
    0x08 -> "link supervision timeout"
    0x13 -> "peer terminated connection"
    0x16 -> "local host terminated connection"
    0x3E -> "connection failed to establish"
    0x85 -> "generic GATT error"
    BluetoothGatt.GATT_CONNECTION_TIMEOUT -> "GATT connection timeout"
    BluetoothGatt.GATT_FAILURE -> "GATT failure"
    else -> "unknown"
}
