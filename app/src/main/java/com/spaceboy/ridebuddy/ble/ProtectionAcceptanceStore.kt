package com.spaceboy.ridebuddy.ble

import android.content.Context
import androidx.core.content.edit

/**
 * Persists the OEM-compatible flag indicating that a motorcycle accepted a protection response.
 * This is a reconnect hint, not cryptographic trust; every connection still verifies the normal
 * companion profile before it is exposed as ready.
 */
internal interface ProtectionAcceptanceStore {
    fun isAccepted(address: BluetoothAddress): Boolean
    fun markAccepted(address: BluetoothAddress)
    fun clear(address: BluetoothAddress)
}

internal class SharedPreferencesProtectionAcceptanceStore(
    context: Context,
    preferencesName: String = PreferencesName,
) : ProtectionAcceptanceStore {
    private val preferences = context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    override fun isAccepted(address: BluetoothAddress): Boolean =
        preferences.getLong(KeyAddress, InvalidAddress) == address.toLong()

    override fun markAccepted(address: BluetoothAddress) {
        preferences.edit { putLong(KeyAddress, address.toLong()) }
    }

    override fun clear(address: BluetoothAddress) {
        if (isAccepted(address)) preferences.edit { remove(KeyAddress) }
    }

    internal companion object {
        // Keep the existing on-device keys; the application is unreleased, but changing them adds
        // no value and would make local hardware testing less predictable.
        const val PreferencesName = "ble_protection_trust"
        const val KeyAddress = "trusted_address"
        const val InvalidAddress = -1L
    }
}
