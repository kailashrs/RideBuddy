package com.spaceboy.ridebuddy.ble

private const val FramedVinLength = 19
private const val VinLength = 17
private val PrintableAsciiRange = 0x20..0x7E

internal fun ByteArray.decodeBikeVin(): String? {
    // Some firmwares return the raw 17-byte VIN; others still wrap it in the
    // 19-byte OEM frame.
    val vinBytes = when (size) {
        VinLength -> this
        FramedVinLength -> copyOfRange(1, size - 1)
        else -> return null
    }
    if (vinBytes.any { (it.toInt() and 0xFF) !in PrintableAsciiRange }) return null
    return vinBytes.toString(Charsets.US_ASCII).takeIf { it.length == VinLength }
}

internal fun ByteArray.decodeClusterSoftwareVersion(): String =
    toString(Charsets.UTF_8).trim('\u0000', ' ', '\r', '\n')
