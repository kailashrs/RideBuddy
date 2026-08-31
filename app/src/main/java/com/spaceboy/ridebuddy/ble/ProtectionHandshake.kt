package com.spaceboy.ridebuddy.ble

/**
 * The challenge/response table the cluster's protection step is satisfied by.
 *
 * The cluster indicates a six-byte challenge and expects the matching six-byte response
 * written back before it will accept the rest of the session. There is no key-derivation
 * function behind this: the vehicle firmware draws from a small fixed set of pairs, so a
 * lookup table is the whole algorithm.
 *
 * Keys are the challenge rendered as upper-case hex, matching [toHex]. An unrecognised
 * challenge yields null and the connection fails cleanly — there is nothing sensible to
 * guess, and writing a wrong response would leave the cluster in a rejected state.
 *
 * Treat the table as a compatibility observation rather than a guarantee: a firmware
 * revision that adds pairs would show up as an unknown-challenge failure, not as
 * corrupted behaviour.
 */
object ProtectionHandshake {
    private val responsesByChallenge = mapOf(
        "6375A3A4633B" to "E977975CC345",
        "D9EADEF2F9A1" to "95C0F8B8D7AE",
        "D6CCAABA9D55" to "A5B85F197336",
        "956D6E55137C" to "EB1DDAED59A8",
        "0A74F652B090" to "FFE5503DEB79",
        "96CEC98CE419" to "5DC0233BA6A1",
        "BD7DC2278205" to "97A5E51A9D95",
        "FB010CD2D1B6" to "311BEB842A20",
        "067141BB6506" to "1B55DB857E10",
        "32B208EE8603" to "CC6EC3092888",
    )

    /** The response to write back, or null when the challenge is not in the table. */
    fun responseFor(challenge: ByteArray): ByteArray? =
        responsesByChallenge[challenge.toHex()]?.hexToByteArray()

    private fun String.hexToByteArray(): ByteArray = chunked(2)
        .map { pair -> pair.toInt(16).toByte() }
        .toByteArray()
}
