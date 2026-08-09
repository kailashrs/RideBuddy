package com.spaceboy.ridebuddy.core.tft

import java.text.Normalizer

object TftCallEncoder {
    fun callerName(value: String): ByteArray {
        val cleaned = Normalizer.normalize(value, Normalizer.Form.NFD)
            .filter { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == ' ' }
            .trim()
            .ifBlank { "Unknown Number" }
        val ascii = cleaned.toByteArray(Charsets.US_ASCII)
        val packet = ByteArray(20)
        packet[0] = 10
        System.arraycopy(ascii, 0, packet, 1, kotlin.math.min(ascii.size, 19))
        return packet
    }

    fun callerNumber(value: String): ByteArray {
        val ascii = value.filter { it in '0'..'9' || it == '+' }.toByteArray(Charsets.US_ASCII)
        val packet = ByteArray(20)
        System.arraycopy(ascii, 0, packet, 0, kotlin.math.min(ascii.size, 20))
        return packet
    }

    fun ringing(): ByteArray = byteArrayOf(1, 0, 0, 1)
    fun accepted(): ByteArray = byteArrayOf(1, 1, 0, 0)
    fun ended(): ByteArray = byteArrayOf(1, 0, 1, 0)
}
