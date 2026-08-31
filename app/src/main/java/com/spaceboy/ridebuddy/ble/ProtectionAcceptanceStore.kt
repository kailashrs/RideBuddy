package com.spaceboy.ridebuddy.ble

import android.content.Context
import androidx.core.content.edit

/**
 * Remembers that a motorcycle has already accepted a protection response.
 *
 * The challenge step only has to run once per bond: on later connections the cluster
 * expects the app to go straight to the normal subscription set, and waiting for a
 * challenge that will never arrive would stall the connection.
 *
 * This is a reconnect hint, not a trust decision. Nothing is skipped on the basis of it
 * except the challenge exchange itself — every connection still verifies the full
 * companion profile before the session is exposed as ready — and the flag is dropped
 * whenever the Android bond for that address goes away.
 */
internal interface ProtectionAcceptanceStore {
    fun isAccepted(address: BluetoothAddress): Boolean
    fun markAccepted(address: BluetoothAddress)
    fun clear(address: BluetoothAddress)
}

/**
 * Shared-preferences implementation. Exactly one address is remembered at a time: the app
 * pairs with one motorcycle, and storing the address rather than a bare boolean means
 * associating a different bike implicitly invalidates the flag.
 */
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

    /** No-op unless [address] is the remembered one, so clearing a stale address is safe. */
    override fun clear(address: BluetoothAddress) {
        if (isAccepted(address)) preferences.edit { remove(KeyAddress) }
    }

    internal companion object {
        // Keep the existing on-device keys; the application is unreleased, but changing them adds
        // no value and would make local hardware testing less predictable.
        const val PreferencesName = "ble_protection_trust"
        const val KeyAddress = "trusted_address"

        /** Sentinel for "nothing stored"; no real address encodes to -1. */
        const val InvalidAddress = -1L
    }
}
