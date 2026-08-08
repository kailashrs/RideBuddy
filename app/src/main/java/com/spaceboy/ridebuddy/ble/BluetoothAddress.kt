package com.spaceboy.ridebuddy.ble

import android.bluetooth.BluetoothDevice

/** Immutable 48-bit Bluetooth address used internally without string round-trips. */
class BluetoothAddress private constructor(private val packed: Long) {
    fun toLong(): Long = packed

    fun toByteArray(): ByteArray = ByteArray(AddressSizeBytes) { index ->
        val shift = (AddressSizeBytes - index - 1) * Byte.SIZE_BITS
        ((packed ushr shift) and 0xFF).toByte()
    }

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

/** Connection-only target. [device] is the exact object supplied by a fresh scan when available. */
data class BikeConnectionTarget(
    val address: BluetoothAddress,
    val deviceName: String,
    val device: BluetoothDevice? = null,
)
