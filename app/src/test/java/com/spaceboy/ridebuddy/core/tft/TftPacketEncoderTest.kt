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
            distanceMetres = 0x01_02_03,
        )

        assertArrayEquals(
            byteArrayOf(1, 11, 0, 0xFF.toByte(), 6, 3, 2, 1, 0x00),
            packet,
        )
    }

    /**
     * The regression this guards is the worst one the cluster can show: a right arrow for a left
     * turn. The pictogram numbers are not ordered by direction, and an earlier table had them
     * mirrored — `TURN_LEFT` sent 6, which is the OEM's *right* arrow.
     *
     * 6 is fixed by a capture: the cluster drew a right arrow for it while the banner read
     * "Turn right onto". The rest are read off the OEM's own `ic_step_*` art.
     */
    @Test
    fun turnPictogramsDoNotMirrorLeftAndRight() {
        assertEquals(6, TftPacketEncoder.clusterManeuver(Maneuver.TURN_RIGHT))
        assertEquals(11, TftPacketEncoder.clusterManeuver(Maneuver.TURN_LEFT))
        assertEquals(5, TftPacketEncoder.clusterManeuver(Maneuver.TURN_SLIGHT_RIGHT))
        assertEquals(10, TftPacketEncoder.clusterManeuver(Maneuver.TURN_SLIGHT_LEFT))
        assertEquals(7, TftPacketEncoder.clusterManeuver(Maneuver.TURN_SHARP_RIGHT))
        assertEquals(12, TftPacketEncoder.clusterManeuver(Maneuver.TURN_SHARP_LEFT))
        assertEquals(4, TftPacketEncoder.clusterManeuver(Maneuver.TURN_KEEP_RIGHT))
        assertEquals(9, TftPacketEncoder.clusterManeuver(Maneuver.TURN_KEEP_LEFT))
        assertEquals(1, TftPacketEncoder.clusterManeuver(Maneuver.STRAIGHT))
    }

    /**
     * 151-157 name the exit the rider takes, and 158 is the plain roundabout. An exit number the
     * cluster has no glyph for must fall back to the plain one rather than wrapping onto a glyph
     * that names a different exit.
     */
    @Test
    fun roundaboutUsesTheExitNumberOnlyWhenThereIsAGlyphForIt() {
        assertEquals(151, TftPacketEncoder.clusterManeuver(Maneuver.ROUNDABOUT_CLOCKWISE, roundaboutExit = 1))
        assertEquals(157, TftPacketEncoder.clusterManeuver(Maneuver.ROUNDABOUT_CLOCKWISE, roundaboutExit = 7))
        assertEquals(158, TftPacketEncoder.clusterManeuver(Maneuver.ROUNDABOUT_CLOCKWISE, roundaboutExit = 8))
        assertEquals(158, TftPacketEncoder.clusterManeuver(Maneuver.ROUNDABOUT_CLOCKWISE))
    }

    /** The OEM's ferry glyph is 200 and its destination marker is 201; these were swapped. */
    @Test
    fun ferryAndDestinationUseTheirOwnGlyphs() {
        assertEquals(200, TftPacketEncoder.clusterManeuver(Maneuver.FERRY_BOAT))
        assertEquals(201, TftPacketEncoder.clusterManeuver(Maneuver.DESTINATION))
        assertEquals(201, TftPacketEncoder.clusterManeuver(Maneuver.UNKNOWN))
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

    /**
     * The OEM rounds the trip packet's maneuver distance and leaves the 8210 one raw. The capture
     * shows 277 m on 8210 against 70 m on 8230 in the same second.
     */
    @Test
    fun onlyTheTripManeuverDistanceIsRoundedToTenMetres() {
        val maneuver = TftPacketEncoder.maneuver(Maneuver.TURN_RIGHT, 0, 0, 277)
        assertEquals(277, le24(maneuver, 5))

        val trip = TftPacketEncoder.trip(0L, destinationDistanceMetres = 0, maneuverDistanceMetres = 277)
        assertEquals(280, le24(trip, 6))
    }

    private fun le24(packet: ByteArray, offset: Int): Int =
        (packet[offset].toInt() and 0xFF) or
            ((packet[offset + 1].toInt() and 0xFF) shl 8) or
            ((packet[offset + 2].toInt() and 0xFF) shl 16)

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

    /**
     * Byte-for-byte against frames the OEM actually put on the wire, taken from an HCI capture of
     * it driving this cluster. If any of these drift, the cluster stops rendering.
     */
    @Test
    fun packetsMatchTheCapturedOemFrames() {
        assertEquals("05ff5300", TftPacketEncoder.session(83).toHex())
        assertEquals("05ff5700", TftPacketEncoder.session(87).toHex())
        assertEquals("ff00", TftPacketEncoder.clear().toHex())
        assertEquals("020000", TftPacketEncoder.speedLimit(0).toHex())
        assertEquals(
            "010600ff0615010000",
            TftPacketEncoder.pictogram(current = 6, next = 6, distanceMetres = 277).toHex(),
        )

        val rows = TftPacketEncoder.guidanceTextRows(
            destination = "Marina Beach Marina Beach Road, ",
            instruction = "Turn right onto Marina Beach Road",
        )
        assertEquals("0400144d6172696e61204265616368204d617200", rows[0].toHex())
        assertEquals("040114696e6120426561636820526f61642c2000", rows[1].toHex())
        assertEquals("0402145475726e207269676874206f6e746f2000", rows[2].toHex())
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun rowText(row: ByteArray): String =
        row.copyOfRange(3, 3 + payloadLength(row)).toString(Charsets.UTF_8)

    private fun payloadLength(row: ByteArray): Int = (row[2].toInt() and 0xFF) - 4

    private fun isPopulated(row: ByteArray): Boolean = payloadLength(row) > 0
}
