package com.spaceboy.ridebuddy.ble

import java.util.UUID

object BleCharacteristics {
    private const val Prefix = "d6328aea-d630-4a83-b51b-1da8e8da"

    val AppEvent: UUID = uuid("8110")
    val NavigationManeuver: UUID = uuid("8210")
    val NavigationSpeedLimit: UUID = uuid("8220")
    val NavigationTrip: UUID = uuid("8230")
    val NavigationText: UUID = uuid("8240")
    val NavigationClear: UUID = uuid("8250")
    val NavigationSession: UUID = uuid("8260")
    val NavigationStatus: UUID = uuid("8270")
    val NavigationControl: UUID = uuid("8280")
    val Telemetry: UUID = uuid("8410")
    val ProtectionChallenge: UUID = uuid("8610")
    val ProtectionResponse: UUID = uuid("8620")
    val CallerName: UUID = uuid("8710")
    val CallEvent: UUID = uuid("8720")
    val CallState: UUID = uuid("8730")
    val CallControl: UUID = uuid("8740")
    /** Used by the India OEM app's SR-family path, not its RS457_ID connection path. */
    val SrMobileStatus: UUID = uuid("8750")
    val CallerNumber: UUID = uuid("8760")
    val ClusterSoftwareVersion: UUID = uuid("8810")
    val Vin: UUID = uuid("8910")

    val PostAuthenticationSubscriptions: List<UUID> = listOf(
        NavigationControl,
        CallEvent,
        CallControl,
        Telemetry,
        ClusterSoftwareVersion,
        Vin,
    )

    /**
     * Optional snapshots taken after the exact OEM subscription sequence is established.
     * Both characteristics advertise READ on the RS457 profile; failures remain non-fatal.
     */
    val PostAuthenticationIdentityReads: List<UUID> = listOf(
        ClusterSoftwareVersion,
        Vin,
    )

    val NavigationWrites: Set<UUID> = setOf(
        NavigationManeuver,
        NavigationSpeedLimit,
        NavigationTrip,
        NavigationText,
        NavigationClear,
        NavigationSession,
        NavigationStatus,
    )

    val ClientCharacteristicConfiguration: UUID =
        UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private fun uuid(suffix: String): UUID = UUID.fromString(Prefix + suffix)
}
