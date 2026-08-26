package com.spaceboy.ridebuddy.ble

data class TelemetryFrame(
    val speedKilometresPerHour: Double,
    val throttlePercent: Int,
    val instantaneousMileageKilometresPerLitre: Double?,
    val engineRpm: Long,
) {
    companion object {
        private const val MinimumFrameLength = 9
        private const val Header = 0x10

        fun parse(payload: ByteArray): TelemetryFrame? {
            if (payload.size < MinimumFrameLength || payload[0].unsigned != Header) return null

            val rawSpeed = payload[1].unsigned or (payload[2].unsigned shl 8)
            val rawRpm = payload[5].unsigned.toLong() or
                (payload[6].unsigned.toLong() shl 8) or
                (payload[7].unsigned.toLong() shl 16) or
                (payload[8].unsigned.toLong() shl 24)
            val rawMileage = payload[4].unsigned

            return TelemetryFrame(
                speedKilometresPerHour = rawSpeed * 0.01,
                throttlePercent = payload[3].unsigned,
                instantaneousMileageKilometresPerLitre = rawMileage
                    .takeIf { it > 0 }
                    ?.times(KilometresPerLitrePerUnit),
                engineRpm = rawRpm,
            )
        }

        private const val KilometresPerLitrePerUnit = 0.2

        private val Byte.unsigned: Int
            get() = toInt() and 0xFF
    }
}
