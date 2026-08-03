package com.spaceboy.ridebuddy.core.tft

object TftCallEncoder {
    fun callerName(value: String): ByteArray {
        val cleaned = value.filter { it.isLetterOrDigit() || it == ' ' }.ifBlank { "Unknown Number" }
        val ascii = cleaned.toByteArray(Charsets.US_ASCII)
        val packet = ByteArray(20)
        packet[0] = 10
        System.arraycopy(ascii, 0, packet, 1, kotlin.math.min(ascii.size, 19))
        return packet
    }

    fun callerNumber(value: String): ByteArray {
        val ascii = value.filter { it.isDigit() || it == '+' }.toByteArray(Charsets.US_ASCII)
        val packet = ByteArray(20)
        System.arraycopy(ascii, 0, packet, 0, kotlin.math.min(ascii.size, 20))
        return packet
    }

    fun ringing(): ByteArray = byteArrayOf(1, 0, 0, 1)
    fun accepted(): ByteArray = byteArrayOf(1, 1, 0, 0)
    fun ended(): ByteArray = byteArrayOf(1, 0, 1, 0)
}
