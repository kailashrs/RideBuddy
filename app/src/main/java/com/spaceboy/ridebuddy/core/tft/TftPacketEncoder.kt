package com.spaceboy.ridebuddy.core.tft

import com.google.android.libraries.mapsplatform.turnbyturn.model.Maneuver
import java.time.Instant
import java.time.ZoneId

/**
 * Builds the fixed-layout packets the instrument cluster's navigation display accepts.
 *
 * The display is not a framebuffer. It draws from a small set of fields — a pictogram, a
 * distance, an arrival time, three short text rows — and each field is written as its own
 * packet on its own characteristic. Every packet here is a complete field value, and all of
 * them are idempotent, so rewriting an unchanged field is harmless.
 *
 * Byte layouts are confirmed against wire captures of a working session rather than
 * inferred, because none of them are self-describing.
 */
object TftPacketEncoder {
    /**
     * Every packet ends with a zero byte.
     *
     * Worth stating explicitly because a plausible-looking alternative exists: the field
     * the terminator sits next to in the protocol is associated with the value 0x2E, which
     * makes 0x2E look like the terminator. It is not. On the wire the session packet is
     * `05 ff 57 00` and the clear packet is `ff 00`, and the maneuver, trip and text
     * packets all end the same way.
     */
    private const val End = 0x00

    /**
     * Maneuver packet from Google maneuver ids, resolving them to cluster pictograms first.
     */
    fun maneuver(
        current: Int, next: Int, roundaboutExit: Int, distanceMetres: Int,
    ): ByteArray =
        pictogram(
            current = clusterManeuver(current),
            next = clusterManeuver(next),
            roundaboutExit = roundaboutExit,
            // Sent raw. This is the one distance field the cluster does not want rounded —
            // a capture shows it carrying 277 m while the trip packet's copy of the same
            // distance, in the same second, carried 280 m. See [trip].
            distanceMetres = distanceMetres,
        )

    /**
     * A maneuver packet built from an already-resolved cluster pictogram.
     *
     * Exposed separately because the pictogram vocabulary carries status as well as turns:
     * [PictogramRecalculating] paired with a "RECALCULATION" banner is how a reroute is
     * shown, and [PictogramSignalLost] with "SIGNAL LOST" is how a GPS dropout is. Neither
     * is a session change — the display stays in guidance and only this field moves.
     *
     * Byte 3 is a fixed `0xFF` delimiter separating the roundabout exit from the next
     * pictogram, not a "no next icon" sentinel.
     */
    fun pictogram(
        current: Int,
        next: Int = 0,
        roundaboutExit: Int = 0,
        distanceMetres: Int = 0,
    ): ByteArray = ByteArray(9).apply {
        this[0] = 1
        this[1] = current.coerceIn(0, 255).toByte()
        this[2] = roundaboutExit.coerceIn(0, 255).toByte()
        this[3] = 0xFF.toByte()
        this[4] = next.coerceIn(0, 255).toByte()
        writeUInt24LittleEndian(offset = 5, value = distanceMetres)
        this[8] = End.toByte()
    }

    /** Cluster pictograms that report state rather than a turn. */
    const val PictogramSignalLost = 202
    const val PictogramRecalculating = 203

    /**
     * Trip packet: arrival time, distance to destination, distance to the next maneuver.
     *
     * The time field is an arrival **wall-clock time** in the phone's zone, not a remaining
     * duration — the display renders it directly as an ETA.
     */
    fun trip(
        arrivalEpochMillis: Long,
        destinationDistanceMetres: Int,
        maneuverDistanceMetres: Int,
    ): ByteArray {
        val arrival = Instant.ofEpochMilli(arrivalEpochMillis).atZone(ZoneId.systemDefault())
        return ByteArray(10).apply {
            this[0] = 3
            this[1] = arrival.minute.toByte()
            this[2] = arrival.hour.toByte()
            writeUInt24LittleEndian(offset = 3, value = destinationDistanceMetres)
            // This copy of the maneuver distance is rounded to the nearest 10 m, while the
            // one in the maneuver packet is sent raw — a capture shows 280 m here against
            // 277 m there in the same second. Clamped before rounding because adding 5 to
            // Int.MAX_VALUE overflows.
            writeUInt24LittleEndian(
                offset = 6,
                value = ((maneuverDistanceMetres.coerceIn(0, MaxUInt24) + 5) / 10) * 10,
            )
            this[9] = End.toByte()
        }
    }

    /**
     * The three text rows are not interchangeable.
     *
     * Rows 0 and 1 are the two bottom lines and carry the destination; row 2 is the banner
     * across the top and carries the turn instruction. Writing only row 0 fills the bottom
     * line and leaves the banner blank, which is what a rider sees if this split is ignored.
     *
     * All three rows are always emitted, so replacing long text with shorter text clears
     * whatever was on the rows the new text does not reach.
     */
    fun guidanceTextRows(destination: String, instruction: String): List<ByteArray> {
        val destinationRows = readableTextRows(destination, maxRows = 2)
        val instructionRows = readableTextRows(instruction, maxRows = 1)
        return listOf(
            textRow(0, destinationRows.getOrElse(0) { ByteArray(0) }),
            textRow(1, destinationRows.getOrElse(1) { ByteArray(0) }),
            textRow(2, instructionRows.getOrElse(0) { ByteArray(0) }),
        )
    }

    /**
     * Spreads one message across every row, for alerts and the parked display test, where there
     * is no destination/instruction split to honour.
     */
    fun displayTextRows(text: String, maxContentRows: Int = 3): List<ByteArray> {
        val chunks = readableTextRows(text, maxRows = maxContentRows.coerceIn(1, 3))
        return (0 until 3).map { row -> textRow(row, chunks.getOrElse(row) { byteArrayOf() }) }
    }

    /** Posted speed limit in km/h. Zero is a valid value and blanks the field. */
    fun speedLimit(kph: Int): ByteArray = byteArrayOf(2, kph.coerceIn(0, 255).toByte(), End.toByte())

    /** Wipes the navigation area. The only packet that does not start with a field tag. */
    fun clear(): ByteArray = byteArrayOf(0xFF.toByte(), End.toByte())

    /** Moves the display between navigation screens; see [TftNavigationBridge] for the values. */
    fun session(state: Int): ByteArray = byteArrayOf(5, 0xFF.toByte(), state.coerceIn(0, 255).toByte(), End.toByte())

    /** Status word accompanying an active session. */
    fun status(code: Int): ByteArray = byteArrayOf(6, code.coerceIn(0, 255).toByte(), End.toByte())

    /**
     * OEM Mappls ids are translated through data/bluetooth/model/a.b. The roundabout
     * glyphs encode exit BEARING, not exit ordinal: plugin/directions/e.a selects them
     * by angle and driving side. Google supplies the same bearing in its maneuver enum;
     * the separate roundaboutTurnNumber belongs only in byte 2 of the maneuver packet.
     */
    fun clusterManeuver(maneuver: Int): Int {
        return when (maneuver) {
            Maneuver.DEPART, Maneuver.STRAIGHT, Maneuver.NAME_CHANGE -> 1
            Maneuver.TURN_U_TURN_CLOCKWISE,
            Maneuver.ON_RAMP_U_TURN_CLOCKWISE,
            Maneuver.OFF_RAMP_U_TURN_CLOCKWISE,
            -> 2
            Maneuver.TURN_U_TURN_COUNTERCLOCKWISE,
            Maneuver.ON_RAMP_U_TURN_COUNTERCLOCKWISE,
            Maneuver.OFF_RAMP_U_TURN_COUNTERCLOCKWISE,
            -> 3
            Maneuver.TURN_KEEP_RIGHT, Maneuver.FORK_RIGHT, Maneuver.ON_RAMP_KEEP_RIGHT -> 4
            Maneuver.TURN_SLIGHT_RIGHT, Maneuver.ON_RAMP_SLIGHT_RIGHT -> 5
            Maneuver.TURN_RIGHT, Maneuver.ON_RAMP_RIGHT -> 6
            Maneuver.TURN_SHARP_RIGHT, Maneuver.ON_RAMP_SHARP_RIGHT, Maneuver.OFF_RAMP_SHARP_RIGHT -> 7
            // The merge glyph carries "you are joining another road" without claiming a side, which
            // is all an unspecified ramp knows.
            Maneuver.MERGE_UNSPECIFIED, Maneuver.MERGE_LEFT, Maneuver.MERGE_RIGHT,
            Maneuver.ON_RAMP_UNSPECIFIED,
            Maneuver.OFF_RAMP_UNSPECIFIED,
            -> 8
            Maneuver.TURN_KEEP_LEFT, Maneuver.FORK_LEFT, Maneuver.ON_RAMP_KEEP_LEFT -> 9
            Maneuver.TURN_SLIGHT_LEFT, Maneuver.ON_RAMP_SLIGHT_LEFT -> 10
            Maneuver.TURN_LEFT, Maneuver.ON_RAMP_LEFT -> 11
            Maneuver.TURN_SHARP_LEFT, Maneuver.ON_RAMP_SHARP_LEFT, Maneuver.OFF_RAMP_SHARP_LEFT -> 12
            Maneuver.OFF_RAMP_RIGHT, Maneuver.OFF_RAMP_KEEP_RIGHT, Maneuver.OFF_RAMP_SLIGHT_RIGHT -> 15
            Maneuver.OFF_RAMP_LEFT, Maneuver.OFF_RAMP_KEEP_LEFT, Maneuver.OFF_RAMP_SLIGHT_LEFT -> 16
            Maneuver.ROUNDABOUT_SHARP_RIGHT_CLOCKWISE -> 151
            Maneuver.ROUNDABOUT_RIGHT_CLOCKWISE -> 152
            Maneuver.ROUNDABOUT_SLIGHT_RIGHT_CLOCKWISE -> 153
            Maneuver.ROUNDABOUT_STRAIGHT_CLOCKWISE -> 154
            Maneuver.ROUNDABOUT_SLIGHT_LEFT_CLOCKWISE -> 155
            Maneuver.ROUNDABOUT_LEFT_CLOCKWISE -> 156
            Maneuver.ROUNDABOUT_SHARP_LEFT_CLOCKWISE -> 157
            Maneuver.ROUNDABOUT_SHARP_RIGHT_COUNTERCLOCKWISE -> 101
            Maneuver.ROUNDABOUT_RIGHT_COUNTERCLOCKWISE -> 102
            Maneuver.ROUNDABOUT_SLIGHT_RIGHT_COUNTERCLOCKWISE -> 103
            Maneuver.ROUNDABOUT_STRAIGHT_COUNTERCLOCKWISE -> 104
            Maneuver.ROUNDABOUT_SLIGHT_LEFT_COUNTERCLOCKWISE -> 105
            Maneuver.ROUNDABOUT_LEFT_COUNTERCLOCKWISE -> 106
            Maneuver.ROUNDABOUT_SHARP_LEFT_COUNTERCLOCKWISE -> 107
            Maneuver.ROUNDABOUT_CLOCKWISE, Maneuver.ROUNDABOUT_COUNTERCLOCKWISE,
            Maneuver.ROUNDABOUT_U_TURN_CLOCKWISE, Maneuver.ROUNDABOUT_U_TURN_COUNTERCLOCKWISE,
            Maneuver.ROUNDABOUT_EXIT_CLOCKWISE, Maneuver.ROUNDABOUT_EXIT_COUNTERCLOCKWISE,
            -> 158
            Maneuver.FERRY_BOAT, Maneuver.FERRY_TRAIN -> 200
            else -> 201
        }
    }

    /**
     * Largest value a 24-bit distance field can carry (about 16 777 km), used to clamp
     * before encoding. Distances are read by the cluster least-significant byte first.
     */
    private const val MaxUInt24 = 0xFF_FFFF

    private fun ByteArray.writeUInt24LittleEndian(offset: Int, value: Int) {
        val safe = value.coerceIn(0, MaxUInt24)
        this[offset] = safe.toByte()
        this[offset + 1] = (safe ushr 8).toByte()
        this[offset + 2] = (safe ushr 16).toByte()
    }

    /** Word wrapping, common road abbreviations and byte-safe truncation for the 16-byte rows. */
    private fun readableTextRows(text: String, maxRows: Int): List<ByteArray> {
        val normalized = text.replace(Regex("[\\p{Cc}\\p{Cf}\\s]+"), " ").trim()
        var remaining = RoadAbbreviations.entries.fold(normalized) { value, (word, short) ->
            value.replace(Regex("\\b$word\\b", RegexOption.IGNORE_CASE), short)
        }
        return buildList {
            repeat(maxRows) { row ->
                if (remaining.isEmpty()) return@buildList
                val prefix = remaining.utf8Prefix(16)
                if (prefix.length == remaining.length) {
                    add(prefix.toByteArray(Charsets.UTF_8))
                    return@buildList
                }
                if (row == maxRows - 1) {
                    val clipped = remaining.utf8Prefix(13).trimEnd()
                    add((clipped + "...").toByteArray(Charsets.UTF_8))
                    return@buildList
                }
                val boundary = prefix.lastIndexOf(' ').takeIf { it > 0 } ?: prefix.length
                add(prefix.substring(0, boundary).toByteArray(Charsets.UTF_8))
                remaining = remaining.substring(boundary).trimStart()
            }
        }
    }

    private fun String.utf8Prefix(byteLimit: Int): String {
        var index = 0
        var bytes = 0
        while (index < length) {
            val codePoint = Character.codePointAt(this, index)
            val count = String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8).size
            if (bytes + count > byteLimit) break
            bytes += count
            index += Character.charCount(codePoint)
        }
        return substring(0, index)
    }

    private val RoadAbbreviations = mapOf(
        "National Highway" to "NH", "State Highway" to "SH",
        "Road" to "Rd", "Street" to "St", "Avenue" to "Ave",
        "Highway" to "Hwy", "Boulevard" to "Blvd", "Junction" to "Jct",
    )

    /**
     * One text-row packet: tag, row index, total packet length, the bytes, terminator.
     * The length byte counts the whole packet, not just its text — the four framing bytes
     * are included.
     */
    private fun textRow(index: Int, chunk: ByteArray): ByteArray {
        val packet = ByteArray(chunk.size + 4)
        packet[0] = 4
        packet[1] = index.toByte()
        packet[2] = (chunk.size + 4).toByte()
        System.arraycopy(chunk, 0, packet, 3, chunk.size)
        packet[packet.lastIndex] = End.toByte()
        return packet
    }
}
