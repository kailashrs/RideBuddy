package com.spaceboy.ridebuddy.ble

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import java.util.UUID

internal data class BikeGattProfileSnapshot(
    val serviceCount: Int,
    val characteristicLabels: List<String>,
    val missingRequiredCharacteristics: List<UUID>,
)

/** Owns the characteristic index and diagnostic snapshot for one discovered GATT profile. */
internal class BikeGattProfile {
    private val characteristics = mutableMapOf<UUID, BluetoothGattCharacteristic>()

    operator fun get(uuid: UUID): BluetoothGattCharacteristic? = characteristics[uuid]

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

private val RequiredConnectionCharacteristics = listOf(
    BleCharacteristics.ProtectionChallenge,
    BleCharacteristics.ProtectionResponse,
) + BleCharacteristics.PostAuthenticationSubscriptions
