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
    fun maneuver(current: Int, next: Int, roundaboutExit: Int, distanceMetres: Int): ByteArray =
        pictogram(
            current = clusterManeuver(current, roundaboutExit),
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
        val destinationRows = utf8Chunks(destination, bytesPerRow = 16, maxRows = 2)
        val instructionRows = utf8Chunks(instruction, bytesPerRow = 16, maxRows = 1)
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
        val chunks = utf8Chunks(text, bytesPerRow = 16, maxRows = maxContentRows.coerceIn(1, 3))
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
     * Cluster pictogram for a Google maneuver.
     *
     * The pictogram numbers are the cluster's own and are not ordered by direction, so they
     * cannot be derived — the mapping below is the vocabulary, established by pairing each
     * id with the arrow the cluster draws for it and confirmed on the wire (the cluster
     * drew a right arrow for `6` while the banner read "Turn right onto"):
     *
     * 1 straight · 2/3 U-turn cw/ccw · 4 keep right · 5 slight right · 6 right · 7 sharp
     * right · 8 merge · 9 keep left · 10 slight left · 11 left · 12 sharp left · 15/16 exit
     * right/left · 151-157 roundabout Nth exit · 158 roundabout · 200 ferry · 201
     * destination.
     *
     * Ids 13 and 14 exist in the vocabulary but no artwork was ever identified for them, so
     * their meaning is unknown and nothing here emits them.
     *
     * A numbered roundabout exit takes priority over the maneuver itself, because the
     * cluster has a dedicated glyph per exit number and that is more informative than the
     * generic turn it would otherwise draw.
     */
    fun clusterManeuver(maneuver: Int, roundaboutExit: Int = 0): Int {
        if (roundaboutExit in 1..7) return 150 + roundaboutExit
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
            Maneuver.TURN_KEEP_RIGHT, Maneuver.FORK_RIGHT -> 4
            Maneuver.TURN_SLIGHT_RIGHT, Maneuver.ON_RAMP_SLIGHT_RIGHT -> 5
            Maneuver.TURN_RIGHT, Maneuver.ON_RAMP_RIGHT -> 6
            Maneuver.TURN_SHARP_RIGHT, Maneuver.ON_RAMP_SHARP_RIGHT, Maneuver.OFF_RAMP_SHARP_RIGHT -> 7
            // The merge glyph carries "you are joining another road" without claiming a side, which
            // is all an unspecified ramp knows.
            Maneuver.MERGE_UNSPECIFIED, Maneuver.MERGE_LEFT, Maneuver.MERGE_RIGHT,
            Maneuver.ON_RAMP_UNSPECIFIED, Maneuver.ON_RAMP_KEEP_LEFT, Maneuver.ON_RAMP_KEEP_RIGHT,
            Maneuver.OFF_RAMP_UNSPECIFIED,
            -> 8
            Maneuver.TURN_KEEP_LEFT, Maneuver.FORK_LEFT -> 9
            Maneuver.TURN_SLIGHT_LEFT, Maneuver.ON_RAMP_SLIGHT_LEFT -> 10
            Maneuver.TURN_LEFT, Maneuver.ON_RAMP_LEFT -> 11
            Maneuver.TURN_SHARP_LEFT, Maneuver.ON_RAMP_SHARP_LEFT, Maneuver.OFF_RAMP_SHARP_LEFT -> 12
            Maneuver.OFF_RAMP_RIGHT, Maneuver.OFF_RAMP_KEEP_RIGHT, Maneuver.OFF_RAMP_SLIGHT_RIGHT -> 15
            Maneuver.OFF_RAMP_LEFT, Maneuver.OFF_RAMP_KEEP_LEFT, Maneuver.OFF_RAMP_SLIGHT_LEFT -> 16
            // A roundabout the SDK gave no exit number for: the plain roundabout glyph, never an
            // exit-numbered one, which would name an exit the rider was never told to take.
            Maneuver.ROUNDABOUT_CLOCKWISE, Maneuver.ROUNDABOUT_COUNTERCLOCKWISE,
            Maneuver.ROUNDABOUT_STRAIGHT_CLOCKWISE, Maneuver.ROUNDABOUT_STRAIGHT_COUNTERCLOCKWISE,
            Maneuver.ROUNDABOUT_LEFT_CLOCKWISE, Maneuver.ROUNDABOUT_LEFT_COUNTERCLOCKWISE,
            Maneuver.ROUNDABOUT_RIGHT_CLOCKWISE, Maneuver.ROUNDABOUT_RIGHT_COUNTERCLOCKWISE,
            Maneuver.ROUNDABOUT_SLIGHT_LEFT_CLOCKWISE, Maneuver.ROUNDABOUT_SLIGHT_LEFT_COUNTERCLOCKWISE,
            Maneuver.ROUNDABOUT_SLIGHT_RIGHT_CLOCKWISE, Maneuver.ROUNDABOUT_SLIGHT_RIGHT_COUNTERCLOCKWISE,
            Maneuver.ROUNDABOUT_SHARP_LEFT_CLOCKWISE, Maneuver.ROUNDABOUT_SHARP_LEFT_COUNTERCLOCKWISE,
            Maneuver.ROUNDABOUT_SHARP_RIGHT_CLOCKWISE, Maneuver.ROUNDABOUT_SHARP_RIGHT_COUNTERCLOCKWISE,
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

    /**
     * Splits [text] into at most [maxRows] rows of at most [bytesPerRow] UTF-8 bytes.
     *
     * The row limit is a *byte* limit, so it is walked by code point rather than by
     * character: splitting a multi-byte character across rows would put an invalid UTF-8
     * fragment on the wire, and a surrogate pair split by index would corrupt the character
     * outright. A single character too large to fit a row at all is skipped, since there is
     * no row it could ever be placed on.
     */
    private fun utf8Chunks(text: String, bytesPerRow: Int, maxRows: Int): List<ByteArray> {
        val chunks = mutableListOf<ByteArray>()
        val current = mutableListOf<Byte>()
        var index = 0
        while (index < text.length && chunks.size < maxRows) {
            val codePoint = Character.codePointAt(text, index)
            val encoded = String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8)
            if (current.isNotEmpty() && current.size + encoded.size > bytesPerRow) {
                chunks += current.toByteArray()
                current.clear()
                if (chunks.size == maxRows) break
            }
            if (encoded.size <= bytesPerRow) current += encoded.toList()
            index += Character.charCount(codePoint)
        }
        if (current.isNotEmpty() && chunks.size < maxRows) chunks += current.toByteArray()
        return chunks
    }

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
