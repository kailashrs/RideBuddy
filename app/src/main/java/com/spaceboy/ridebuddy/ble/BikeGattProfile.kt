package com.spaceboy.ridebuddy.ble

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import java.util.UUID

/**
 * What service discovery found, in a form safe to log and show on the diagnostics screen.
 *
 * [missingRequiredCharacteristics] is the part the connection acts on: a non-empty list
 * means the peer is not a compatible cluster (or discovery was incomplete) and the
 * connection is failed rather than half-driven.
 */
internal data class BikeGattProfileSnapshot(
    val serviceCount: Int,
    val characteristicLabels: List<String>,
    val missingRequiredCharacteristics: List<UUID>,
)

/**
 * Owns the characteristic index for one discovered GATT profile.
 *
 * Characteristics are looked up by UUID across *all* discovered services rather than
 * under one hardcoded service UUID, because the vendor service UUID is not advertised
 * and is not guaranteed to be stable across firmware revisions. The UUID family is
 * distinctive enough that a flat index cannot collide.
 */
internal class BikeGattProfile {
    private val characteristics = mutableMapOf<UUID, BluetoothGattCharacteristic>()

    operator fun get(uuid: UUID): BluetoothGattCharacteristic? = characteristics[uuid]

    /**
     * Rebuilds the index from a fresh discovery result and reports what it contains.
     *
     * Called on every `onServicesDiscovered`, including after a reconnect, so the index
     * is cleared first: a `BluetoothGattCharacteristic` handle from a previous session is
     * not valid against a new one.
     */
    fun replace(services: List<BluetoothGattService>): BikeGattProfileSnapshot {
        characteristics.clear()
        services.flatMap { it.characteristics }.forEach { characteristic ->
            characteristics[characteristic.uuid] = characteristic
        }
        return BikeGattProfileSnapshot(
            serviceCount = services.size,
            characteristicLabels = services.flatMap { service ->
                service.characteristics.map { characteristic ->
                    "${service.uuid.shortName()}/${characteristic.uuid.shortName()} props=0x${
                        characteristic.properties.toString(16)
                    }"
                }
            },
            missingRequiredCharacteristics = RequiredConnectionCharacteristics
                .filterNot(characteristics::containsKey),
        )
    }

    fun clear() {
        characteristics.clear()
    }
}

/**
 * The characteristics without which a connection cannot proceed: the protection pair
 * needed to authenticate, and the notification set the session runs on afterwards.
 *
 * Deliberately excludes the HID-over-GATT service — Android hides it from unprivileged
 * apps, so requiring it would fail every connection. See [BikeHogpServiceUuidString].
 */
private val RequiredConnectionCharacteristics = listOf(
    BleCharacteristics.ProtectionChallenge,
    BleCharacteristics.ProtectionResponse,
) + BleCharacteristics.PostAuthenticationSubscriptions
