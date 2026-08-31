package com.spaceboy.ridebuddy.ble

/** A VIN wrapped in a one-byte header and one-byte trailer. */
private const val FramedVinLength = 19

/** A bare VIN, as defined by ISO 3779. */
private const val VinLength = 17

private val PrintableAsciiRange = 0x20..0x7E

/**
 * Decodes a VIN read from the cluster, or returns null when the value is not one.
 *
 * Two encodings are seen in the field: some firmware returns the bare 17-byte VIN and
 * some wraps it in a 19-byte frame whose first and last bytes are stripped. Any other
 * length, or any non-printable byte, means the read returned something that is not a
 * VIN — an uninitialised buffer, for instance — and is rejected rather than shown to the
 * rider as garbage.
 */
internal fun ByteArray.decodeBikeVin(): String? {
    val vinBytes = when (size) {
        VinLength -> this
        FramedVinLength -> copyOfRange(1, size - 1)
        else -> return null
    }
    if (vinBytes.any { (it.toInt() and 0xFF) !in PrintableAsciiRange }) return null
    return vinBytes.toString(Charsets.US_ASCII).takeIf { it.length == VinLength }
}

/**
 * Decodes the cluster firmware version, which arrives as text that may be zero-padded to
 * a fixed buffer width or newline-terminated depending on firmware.
 */
internal fun ByteArray.decodeClusterSoftwareVersion(): String =
    toString(Charsets.UTF_8).trim('\u0000', ' ', '\r', '\n')
