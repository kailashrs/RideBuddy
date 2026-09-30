package com.spaceboy.ridebuddy.ble

import android.net.MacAddress
import com.spaceboy.ridebuddy.domain.BikeIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * The identity read off the selected motorcycle. Address-scoped: a stored identity belonging to
 * another bike reads as empty rather than showing that bike's VIN.
 */
internal class BikeIdentityRepository(
    private val linkState: LinkStateStore,
    scope: CoroutineScope,
) {
    private val selectedAddress = MutableStateFlow<MacAddress?>(null)

    val identity: StateFlow<BikeIdentity> = combine(selectedAddress, linkState.state) { address, state ->
        state.identity?.takeIf { address != null && it.address == address.toString() }?.toBikeIdentity()
            ?: BikeIdentity()
    }.stateIn(scope, SharingStarted.Eagerly, BikeIdentity())

    fun select(address: MacAddress) {
        selectedAddress.value = address
    }

    /**
     * Applies a live value read from [address]. Ignored when that is not the selected bike — a
     * late callback from a superseded connection. Fields not read this session survive.
     */
    fun update(address: MacAddress, transform: (BikeIdentity) -> BikeIdentity) {
        if (selectedAddress.value != address) return
        val key = address.toString()
        linkState.update { state ->
            val current = state.identity?.takeIf { it.address == key }?.toBikeIdentity() ?: BikeIdentity()
            val updated = transform(current)
            state.copy(
                identity = StoredBikeIdentity(
                    address = key,
                    vin = updated.vin,
                    clusterSoftwareVersion = updated.clusterSoftwareVersion,
                    lastConnectedAtMillis = updated.lastConnectedAtMillis,
                ),
            )
        }
    }

    fun clear(address: MacAddress) {
        if (selectedAddress.value == address) selectedAddress.value = null
        val key = address.toString()
        linkState.update { state -> if (state.identity?.address == key) state.copy(identity = null) else state }
    }
}
