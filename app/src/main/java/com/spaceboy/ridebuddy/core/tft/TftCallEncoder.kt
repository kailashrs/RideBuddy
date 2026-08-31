package com.spaceboy.ridebuddy.core.tft

import java.text.Normalizer

/**
 * Builds the packets that drive the cluster's call screen.
 *
 * Three fields make up that screen — caller name, caller number, and call state — each on
 * its own characteristic and each a fixed-width buffer. Nothing here is free text: the
 * display renders a fixed layout, so values are sanitised and truncated to fit rather than
 * being sent as-is and clipped by the firmware.
 */
object TftCallEncoder {
    /**
     * Caller name as a 20-byte packet: a `0x0A` tag then up to 19 zero-padded ASCII bytes.
     *
     * The cluster's font has no accented glyphs, so the name is NFD-normalised first —
     * decomposing "é" into "e" plus a combining mark — and then filtered to letters, digits
     * and spaces. The letter survives where a plain filter would have dropped the whole
     * character. A name left empty by that filtering falls back to a label rather than
     * showing a blank row next to a ringing phone.
     */
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
     * Caller number, right-truncated to the ten characters the cluster's field holds.
     *
     * Keeping the *last* ten is what makes an international number readable: it drops the
     * country code and leaves the local significant digits, which is the part a rider
     * recognises. Sending more than ten overflows the field.
     *
     * Digits are filtered before truncating, not after. A number carrying spaces or
     * brackets would otherwise spend some of those ten characters on punctuation and lose
     * real digits off the front.
     */
    fun callerNumber(value: String): ByteArray {
        val ascii = value.filter { it in '0'..'9' || it == '+' }
            .takeLast(ClusterNumberLength)
            .toByteArray(Charsets.US_ASCII)
        val packet = ByteArray(PacketLength)
        System.arraycopy(ascii, 0, packet, 0, kotlin.math.min(ascii.size, PacketLength))
        return packet
    }

    // Call state is `[0x01, answered, ended, direction]`. Direction 1 is an incoming call
    // still ringing; direction 2 is one dialled from this phone and is held for the whole
    // of an outgoing call, which is never marked answered — the cluster has no way to know
    // when the far end picks up.

    /** Incoming call, ringing. */
    fun ringing(): ByteArray = byteArrayOf(1, 0, 0, 1)

    /** Call answered and in progress. Direction is cleared; the screen no longer needs it. */
    fun accepted(): ByteArray = byteArrayOf(1, 1, 0, 0)

    /** Outgoing call, from ring-out until it ends. */
    fun outgoing(): ByteArray = byteArrayOf(1, 0, 0, 2)

    /** Call over. Always sent last, so no call is left showing on the display. */
    fun ended(): ByteArray = byteArrayOf(1, 0, 1, 0)

    /** Fixed buffer width for both the name and number fields. */
    private const val PacketLength = 20

    /** Characters the cluster's number field can display. */
    private const val ClusterNumberLength = 10
}
