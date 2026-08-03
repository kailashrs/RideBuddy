package com.spaceboy.ridebuddy.ble

data class TelemetryFrame(
    val speedKilometresPerHour: Double,
    val throttlePercent: Int,
    val instantaneousConsumptionLitresPer100Km: Double,
    val engineRpm: Long,
) {
    companion object {
        private const val FrameLength = 10
        private const val Header = 0x10
        private const val Terminator = 0x23

        fun parse(payload: ByteArray): TelemetryFrame? {
            if (payload.size != FrameLength) return null
            if (payload[0].unsigned != Header || payload[9].unsigned != Terminator) return null

            val rawSpeed = payload[1].unsigned or (payload[2].unsigned shl 8)
            val rawRpm = payload[5].unsigned.toLong() or
                (payload[6].unsigned.toLong() shl 8) or
                (payload[7].unsigned.toLong() shl 16) or
                (payload[8].unsigned.toLong() shl 24)

            return TelemetryFrame(
                speedKilometresPerHour = rawSpeed * 0.01,
                throttlePercent = payload[3].unsigned,
                instantaneousConsumptionLitresPer100Km = payload[4].unsigned * 0.2,
                engineRpm = rawRpm,
            )
        }

        private val Byte.unsigned: Int
            get() = toInt() and 0xFF
    }
}
