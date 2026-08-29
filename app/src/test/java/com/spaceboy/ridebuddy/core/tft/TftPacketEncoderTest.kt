package com.spaceboy.ridebuddy.core.tft

import com.google.android.libraries.mapsplatform.turnbyturn.model.Maneuver
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TftPacketEncoderTest {
    @Test
    fun maneuverPacketUsesOemLittleEndianDistanceLayout() {
        val packet = TftPacketEncoder.maneuver(
            current = Maneuver.TURN_LEFT,
            next = Maneuver.TURN_RIGHT,
            roundaboutExit = 0,
            // Rounded to the nearest 10 m as the OEM does: 66051 -> 66050 -> 0x010202.
            distanceMetres = 0x01_02_03,
        )

        assertArrayEquals(
            byteArrayOf(1, 6, 0, 0xFF.toByte(), 1, 2, 2, 1, 0x00),
            packet,
        )
    }

    /**
     * The regression this guards: written most-significant byte first, every distance below
     * 65,536 m — which is nearly all of them — reached the cluster as a wildly different value,
     * and anything under 256 m arrived as zero.
     */
    @Test
    fun shortManeuverDistanceKeepsItsLowByteFirst() {
        val packet = TftPacketEncoder.maneuver(
            current = Maneuver.TURN_RIGHT,
            next = 0,
            roundaboutExit = 0,
            distanceMetres = 500,
        )

        // 500 == 0x0001F4, so the OEM puts F4 in the first distance byte.
        assertArrayEquals(byteArrayOf(0xF4.toByte(), 1, 0), packet.copyOfRange(5, 8))
    }

    @Test
    fun tripPacketMatchesOemFieldAndByteOrder() {
        val arrival = ZonedDateTime.of(2026, 3, 14, 9, 26, 0, 0, ZoneId.systemDefault())

        val packet = TftPacketEncoder.trip(
            arrivalEpochMillis = arrival.toInstant().toEpochMilli(),
            destinationDistanceMetres = 0x01_02_03,
            maneuverDistanceMetres = 500,
        )

        assertArrayEquals(
            byteArrayOf(
                3,
                // The arrival clock is read back from the same instant, so a zone whose DST gap
                // shifts 09:26 cannot make this flaky.
                arrival.minute.toByte(),
                arrival.hour.toByte(),
                // Destination distance first, then distance to the current maneuver.
                3, 2, 1,
                0xF4.toByte(), 1, 0,
                0x00,
            ),
            packet,
        )
    }

    @Test
    fun distanceFieldsClampToTwentyFourBits() {
        val packet = TftPacketEncoder.maneuver(
            current = Maneuver.STRAIGHT,
            next = 0,
            roundaboutExit = 0,
            distanceMetres = Int.MAX_VALUE,
        )

        assertArrayEquals(
            byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()),
            packet.copyOfRange(5, 8),
        )
    }

    /** Rounding keeps the cluster from redrawing the distance for every metre. */
    @Test
    fun maneuverDistanceIsRoundedToTheNearestTenMetres() {
        fun distance(m: Int) =
            TftPacketEncoder.maneuver(Maneuver.TURN_RIGHT, 0, 0, m).copyOfRange(5, 8)
                .let { it[0].toInt() and 0xFF or ((it[1].toInt() and 0xFF) shl 8) }

        assertEquals(280, distance(277))
        assertEquals(70, distance(70))
        assertEquals(0, distance(4))
        assertEquals(10, distance(5))
    }

    @Test
    fun textIsSplitIntoSixteenByteRows() {
        val rows = TftPacketEncoder.displayTextRows("1234567890abcdefghijklmnopqrstuv")

        assertEquals(2, rows.count(::isPopulated))
        assertEquals(0, rows[0][1].toInt())
        assertEquals(1, rows[1][1].toInt())
        assertEquals(0x00, rows[1].last().toInt() and 0xFF)
    }

    @Test
    fun textRowMatchesOemLengthAndTerminatorLayout() {
        val row = TftPacketEncoder.displayTextRows("A", maxContentRows = 1).first()

        assertArrayEquals(byteArrayOf(4, 0, 5, 'A'.code.toByte(), 0x00), row)
        assertEquals(row.size, row[2].toInt() and 0xFF)
    }

    @Test
    fun textRowsNeverSplitUtf8CodePoints() {
        val rows = TftPacketEncoder.displayTextRows("MG रोड դեպի café 🚦 आगे")

        rows.forEach { row ->
            val payloadLength = payloadLength(row)
            val decoded = row.copyOfRange(3, 3 + payloadLength).toString(Charsets.UTF_8)
            assertFalse(decoded.contains('\uFFFD'))
            assert(payloadLength <= 16)
        }
    }

    @Test
    fun compactTextUsesOneRow() {
        val rows = TftPacketEncoder.displayTextRows("A road name longer than one row", maxContentRows = 1)

        assertEquals(1, rows.count(::isPopulated))
    }

    @Test
    fun displayTextClearsEveryUnusedRow() {
        val rows = TftPacketEncoder.displayTextRows("Rain alert")

        assertEquals(3, rows.size)
        assertArrayEquals(byteArrayOf(4, 1, 4, 0x00), rows[1])
        assertArrayEquals(byteArrayOf(4, 2, 4, 0x00), rows[2])
    }

    /**
     * Rows 0 and 1 are the two bottom lines and row 2 is the top banner. The capture shows the OEM
     * writing "Marina Beach Mar" / "ina Beach Road, " / "Turn right onto " into exactly that order.
     */
    @Test
    fun guidanceTextPutsTheDestinationBelowAndTheInstructionOnTop() {
        val rows = TftPacketEncoder.guidanceTextRows(
            destination = "Marina Beach Marina Beach Road, Chennai",
            instruction = "Turn right onto Marina Beach Road",
        )

        assertEquals(3, rows.size)
        assertEquals("Marina Beach Mar", rowText(rows[0]))
        assertEquals("ina Beach Road, ", rowText(rows[1]))
        assertEquals("Turn right onto ", rowText(rows[2]))
        rows.forEach { assertEquals(0x00, it.last().toInt() and 0xFF) }
    }

    @Test
    fun guidanceTextClearsRowsItHasNothingFor() {
        val rows = TftPacketEncoder.guidanceTextRows(destination = "", instruction = "Head east")

        assertArrayEquals(byteArrayOf(4, 0, 4, 0x00), rows[0])
        assertArrayEquals(byteArrayOf(4, 1, 4, 0x00), rows[1])
        assertEquals("Head east", rowText(rows[2]))
    }

    @Test
    fun clearPacketMatchesClusterEnvelope() {
        assertArrayEquals(
            byteArrayOf(0xFF.toByte(), 0x00),
            TftPacketEncoder.clear(),
        )
    }

    @Test
    fun sessionPacketUsesDedicatedSessionEnvelope() {
        assertArrayEquals(
            byteArrayOf(5, 0xFF.toByte(), 80, 0x00),
            TftPacketEncoder.session(80),
        )
    }

    private fun rowText(row: ByteArray): String =
        row.copyOfRange(3, 3 + payloadLength(row)).toString(Charsets.UTF_8)

    private fun payloadLength(row: ByteArray): Int = (row[2].toInt() and 0xFF) - 4

    private fun isPopulated(row: ByteArray): Boolean = payloadLength(row) > 0
}
