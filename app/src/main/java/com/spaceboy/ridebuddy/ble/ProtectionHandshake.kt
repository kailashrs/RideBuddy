package com.spaceboy.ridebuddy.ble

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

    fun responseFor(challenge: ByteArray): ByteArray? = responsesByChallenge[
        challenge.toHex(),
    ]?.hexToByteArray()



    private fun String.hexToByteArray(): ByteArray = chunked(2)
        .map { pair -> pair.toInt(16).toByte() }
        .toByteArray()
}
