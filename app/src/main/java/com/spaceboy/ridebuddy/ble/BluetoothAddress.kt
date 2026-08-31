package com.spaceboy.ridebuddy.ble

import com.spaceboy.ridebuddy.domain.ConnectionAttemptTrigger

/**
 * An immutable 48-bit Bluetooth address, held packed into a `Long`.
 *
 * Addresses cross a lot of boundaries here — companion associations, GATT callbacks,
 * persisted settings, log lines — and the platform hands them over in three different
 * shapes (text, bytes, and a packed value). Normalising to one packed representation
 * means comparisons are a single `Long` equality rather than a case-sensitive string
 * match, and there is one place where a malformed address is rejected.
 *
 * Construction is private and every factory returns null on bad input, so an instance is
 * always a well-formed address.
 */
class BluetoothAddress private constructor(private val packed: Long) {
    fun toLong(): Long = packed

    /** Big-endian bytes, the order the platform's connection APIs expect. */
    fun toByteArray(): ByteArray = ByteArray(AddressSizeBytes) { index ->
        val shift = (AddressSizeBytes - index - 1) * Byte.SIZE_BITS
        ((packed ushr shift) and 0xFF).toByte()
    }

    /** Canonical upper-case colon-separated form, e.g. `AA:BB:CC:DD:EE:FF`. */
    override fun toString(): String = buildString(CanonicalAddressLength) {
        toByteArray().forEachIndexed { index, byte ->
            if (index > 0) append(':')
            val value = byte.toInt() and 0xFF
            append(HexDigits[value ushr 4])
            append(HexDigits[value and 0x0F])
        }
    }

    override fun equals(other: Any?): Boolean = other is BluetoothAddress && packed == other.packed

    override fun hashCode(): Int = packed.hashCode()

    companion object {
        private const val AddressSizeBytes = 6
        private const val CanonicalAddressLength = 17
        private const val HexDigits = "0123456789ABCDEF"
        private const val MaximumPackedAddress = 0xFFFFFFFFFFFFL

        /** Reads back a persisted address, rejecting anything wider than 48 bits. */
        fun fromLong(value: Long): BluetoothAddress? =
            value.takeIf { it in 0..MaximumPackedAddress }?.let(::BluetoothAddress)

        fun fromBytes(bytes: ByteArray?): BluetoothAddress? {
            if (bytes?.size != AddressSizeBytes) return null
            var packed = 0L
            bytes.forEach { byte -> packed = (packed shl Byte.SIZE_BITS) or (byte.toLong() and 0xFF) }
            return BluetoothAddress(packed)
        }

        /** Parses text-only platform callback values. Connection code uses [toByteArray], not this text. */
        fun parse(value: String?): BluetoothAddress? {
            val text = value?.trim()?.takeIf { it.length == CanonicalAddressLength } ?: return null
            var packed = 0L
            repeat(AddressSizeBytes) { index ->
                val offset = index * 3
                if (index > 0 && text[offset - 1] != ':') return null
                val high = text[offset].hexValue() ?: return null
                val low = text[offset + 1].hexValue() ?: return null
                packed = (packed shl Byte.SIZE_BITS) or ((high shl 4) or low).toLong()
            }
            return BluetoothAddress(packed)
        }

        private fun Char.hexValue(): Int? = when (this) {
            in '0'..'9' -> code - '0'.code
            in 'A'..'F' -> code - 'A'.code + 10
            in 'a'..'f' -> code - 'a'.code + 10
            else -> null
        }
    }
}

/**
 * Connection-only target resolved from a Companion Device Manager association. The
 * target carries the packed [address] and the user-visible [deviceName]; the platform
 * [android.bluetooth.BluetoothDevice] is looked up on demand from the adapter when a
 * connection is established.
 *
 * [trigger] travels with the request so diagnostics can distinguish a user-initiated connect from
 * one a companion presence callback asked for. Automatic retries are owned by the connection
 * itself and are never expressed as a new target.
 */
data class BikeConnectionTarget(
    val address: BluetoothAddress,
    val deviceName: String,
    val trigger: ConnectionAttemptTrigger = ConnectionAttemptTrigger.UserRequest,
)
