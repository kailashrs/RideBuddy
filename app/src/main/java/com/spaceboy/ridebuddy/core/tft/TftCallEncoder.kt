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

    /**
     * The cluster's number field holds ten characters. The OEM writes `takeLast(10)` of the raw
     * number, which on an international number drops the country code and leaves the local
     * significant digits; sending more than that is what overflows the field. The digits are
     * filtered first — the OEM truncates the raw string, so a number carrying spaces or brackets
     * leaves it sending punctuation.
     */
    fun callerNumber(value: String): ByteArray {
        val ascii = value.filter { it in '0'..'9' || it == '+' }
            .takeLast(ClusterNumberLength)
            .toByteArray(Charsets.US_ASCII)
        val packet = ByteArray(PacketLength)
        System.arraycopy(ascii, 0, packet, 0, kotlin.math.min(ascii.size, PacketLength))
        return packet
    }

    /**
     * `8730` carries `[0x01, answered, ended, direction]`.
     *
     * Direction 1 is an incoming call still ringing and 2 is one dialled from this phone; the OEM
     * holds 2 for the whole of an outgoing call and never marks it answered.
     */
    fun ringing(): ByteArray = byteArrayOf(1, 0, 0, 1)
    fun accepted(): ByteArray = byteArrayOf(1, 1, 0, 0)
    fun outgoing(): ByteArray = byteArrayOf(1, 0, 0, 2)
    fun ended(): ByteArray = byteArrayOf(1, 0, 1, 0)

    private const val PacketLength = 20
    private const val ClusterNumberLength = 10
}
