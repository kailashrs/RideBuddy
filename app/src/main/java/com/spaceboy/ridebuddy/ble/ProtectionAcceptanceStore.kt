package com.spaceboy.ridebuddy.ble

import android.net.MacAddress
/**
 * Remembers that a motorcycle has already accepted a protection response.
 *
 * The challenge step only has to run once per bond: on later connections the cluster expects
 * the app to go straight to the normal subscription set, and waiting for a challenge that
 * will never arrive would stall the connection. This is a reconnect hint, not a trust
 * decision — every connection still verifies the full profile — and it is dropped whenever
 * the Android bond for that address goes away.
 */
internal interface ProtectionAcceptanceStore {
    fun isAccepted(address: MacAddress): Boolean
    fun markAccepted(address: MacAddress)
    fun clear(address: MacAddress)
}

/** One address at a time: associating a different bike implicitly invalidates the flag. */
internal class LinkStateProtectionAcceptanceStore(private val linkState: LinkStateStore) : ProtectionAcceptanceStore {
    override fun isAccepted(address: MacAddress): Boolean =
        linkState.state.value.protectionAcceptedAddress == address.toString()

    override fun markAccepted(address: MacAddress) {
        linkState.update { it.copy(protectionAcceptedAddress = address.toString()) }
    }

    override fun clear(address: MacAddress) {
        linkState.update { state ->
            if (state.protectionAcceptedAddress == address.toString()) state.copy(protectionAcceptedAddress = null) else state
        }
    }
}
