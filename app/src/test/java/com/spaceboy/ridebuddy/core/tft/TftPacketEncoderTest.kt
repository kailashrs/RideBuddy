package com.spaceboy.ridebuddy.core.tft

import com.google.android.libraries.mapsplatform.turnbyturn.model.Maneuver
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TftPacketEncoderTest {
    @Test
    fun maneuverPacketUsesOemBigEndianDistanceLayout() {
        val packet = TftPacketEncoder.maneuver(
            current = Maneuver.TURN_LEFT,
            next = Maneuver.TURN_RIGHT,
            roundaboutExit = 0,
            distanceMetres = 0x01_02_03,
        )

        assertArrayEquals(
            byteArrayOf(1, 6, 0, 0xFF.toByte(), 1, 1, 2, 3, 0x2E),
            packet,
        )
    }

    @Test
    fun textIsSplitIntoThreeSixteenByteRows() {
        val rows = TftPacketEncoder.textRows("1234567890abcdefghijklmnopqrstuv")

        assertEquals(2, rows.size)
        assertEquals(0, rows[0][1].toInt())
        assertEquals(1, rows[1][1].toInt())
        assertEquals(0x2E, rows[1].last().toInt() and 0xFF)
    }

    @Test
    fun textRowMatchesOemLengthAndTerminatorLayout() {
        val row = TftPacketEncoder.textRows("A", maxRows = 1).single()

        assertArrayEquals(byteArrayOf(4, 0, 5, 'A'.code.toByte(), 0x2E), row)
        assertEquals(row.size, row[2].toInt() and 0xFF)
    }

    @Test
    fun textRowsNeverSplitUtf8CodePoints() {
        val rows = TftPacketEncoder.textRows("MG रोड դեպի café 🚦 आगे")

        rows.forEach { row ->
            val payloadLength = (row[2].toInt() and 0xFF) - 4
            val decoded = row.copyOfRange(3, 3 + payloadLength).toString(Charsets.UTF_8)
            assertFalse(decoded.contains('\uFFFD'))
            assert(payloadLength <= 16)
        }
    }

    @Test
    fun compactTextUsesOneRow() {
        assertEquals(1, TftPacketEncoder.textRows("A road name longer than one row", maxRows = 1).size)
    }

    @Test
    fun displayTextClearsEveryUnusedRow() {
        val rows = TftPacketEncoder.displayTextRows("Rain alert")

        assertEquals(3, rows.size)
        assertArrayEquals(byteArrayOf(4, 1, 4, 0x2E), rows[1])
        assertArrayEquals(byteArrayOf(4, 2, 4, 0x2E), rows[2])
    }

    @Test
    fun clearPacketMatchesClusterEnvelope() {
        assertArrayEquals(
            byteArrayOf(0xFF.toByte(), 0x2E),
            TftPacketEncoder.clear(),
        )
    }

    @Test
    fun sessionPacketUsesDedicatedSessionEnvelope() {
        assertArrayEquals(
            byteArrayOf(5, 0xFF.toByte(), 80, 0x2E),
            TftPacketEncoder.session(80),
        )
    }
}
