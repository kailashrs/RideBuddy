package com.spaceboy.ridebuddy.ble

import java.util.UUID

/**
 * The motorcycle's application characteristics, named by role.
 *
 * Every characteristic in the vehicle profile shares one 128-bit UUID family and differs
 * only in the final four hex digits, so each entry below is built from its suffix. The
 * suffixes are the stable names used throughout the code, the logs, and
 * `docs/aprilia-rs457-ble-protocol.md`.
 *
 * Direction is fixed per characteristic: phone→bike entries are written, bike→phone
 * entries are subscribed to and arrive through the GATT notification callback.
 */
object BleCharacteristics {
    private const val Prefix = "d6328aea-d630-4a83-b51b-1da8e8da"

    /** phone→bike. Notification-icon state and phone battery level. */
    val AppEvent: UUID = uuid("8110")

    /** phone→bike. Current and next turn pictogram plus distance to the current turn. */
    val NavigationManeuver: UUID = uuid("8210")

    /** phone→bike. Posted speed limit for the current road, in km/h. */
    val NavigationSpeedLimit: UUID = uuid("8220")

    /** phone→bike. Arrival wall-clock time, distance to destination, distance to turn. */
    val NavigationTrip: UUID = uuid("8230")

    /** phone→bike. One of the three short text rows the display draws. */
    val NavigationText: UUID = uuid("8240")

    /** phone→bike. Wipes the navigation area of the display. */
    val NavigationClear: UUID = uuid("8250")

    /** phone→bike. Which navigation screen the cluster should be showing. */
    val NavigationSession: UUID = uuid("8260")

    /** phone→bike. Navigation status/command word that accompanies an active session. */
    val NavigationStatus: UUID = uuid("8270")

    /** bike→phone. Handlebar navigation control: start route, skip waypoint, or exit. */
    val NavigationControl: UUID = uuid("8280")

    /** bike→phone. Live vehicle telemetry: speed, throttle, mileage, RPM. */
    val Telemetry: UUID = uuid("8410")

    /** bike→phone. Protection challenge, delivered as an indication. */
    val ProtectionChallenge: UUID = uuid("8610")

    /** phone→bike. Protection response matching the received challenge. */
    val ProtectionResponse: UUID = uuid("8620")

    /** phone→bike. Caller name shown on the call screen. */
    val CallerName: UUID = uuid("8710")

    /** bike→phone. Subscribed as part of the normal set; no consumer identified yet. */
    val CallEvent: UUID = uuid("8720")

    /** phone→bike. Whether a call is up, and whether it is incoming or outgoing. */
    val CallState: UUID = uuid("8730")

    /** bike→phone. Handlebar call control, and the cluster's own readiness announcement. */
    val CallControl: UUID = uuid("8740")

    /** phone→bike. Caller number shown on the call screen. */
    val CallerNumber: UUID = uuid("8760")

    /** bike→phone. Cluster firmware version string. */
    val ClusterSoftwareVersion: UUID = uuid("8810")

    /** bike→phone. Vehicle identification number. */
    val Vin: UUID = uuid("8910")

    /**
     * The characteristics subscribed to once authentication has completed.
     *
     * Set membership is fixed by the vehicle profile; the *order* is this app's choice.
     * Enabling them as a deterministic queue — rather than in whatever order the GATT
     * service scan happens to yield — keeps failures reproducible and lets
     * [PostAuthenticationGate] know exactly which subscriptions are still outstanding.
     */
    val PostAuthenticationSubscriptions: List<UUID> = listOf(
        NavigationControl,
        CallEvent,
        CallControl,
        Telemetry,
        ClusterSoftwareVersion,
        Vin,
    )

    /**
     * The SIG-standard Client Characteristic Configuration descriptor. Subscribing to a
     * bike→phone characteristic means writing the enable value to this descriptor on it.
     */
    val ClientCharacteristicConfiguration: UUID =
        UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private fun uuid(suffix: String): UUID = UUID.fromString(Prefix + suffix)
}
