package com.spaceboy.ridebuddy.core.tft

import com.google.android.libraries.mapsplatform.turnbyturn.model.Maneuver
import java.time.Instant
import java.time.ZoneId

object TftPacketEncoder {
    /**
     * Every packet ends with a zero byte.
     *
     * Static analysis of the OEM app suggested 0x2E, because the field it terminates with is
     * declared next to a `= 46` assignment. An HCI capture of the OEM driving this cluster settles
     * it: its session packet is `05 ff 57 00`, its clear is `ff 00`, and its maneuver, trip and
     * text packets all end the same way. The 0x2E field is written but never read.
     */
    private const val End = 0x00

    fun maneuver(current: Int, next: Int, roundaboutExit: Int, distanceMetres: Int): ByteArray =
        pictogram(
            current = clusterManeuver(current, roundaboutExit),
            next = clusterManeuver(next),
            roundaboutExit = roundaboutExit,
            // Sent raw. The capture shows the OEM's 8210 distance at 277 m, so this is the one
            // field it does not round.
            distanceMetres = distanceMetres,
        )

    /**
     * A maneuver packet built from an already-resolved cluster pictogram.
     *
     * The cluster's pictogram vocabulary carries status as well as turns: the OEM writes 203 with
     * a "RECALCULATION" banner while it reroutes, and 202 with "SIGNAL LOST" when it loses GPS.
     * Neither is a session change.
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
            // The OEM rounds this second distance to the nearest 10 m and leaves the 8210 one
            // raw; the capture shows 70 m here against 277 m there. Clamped first because
            // rounding Int.MAX_VALUE overflows.
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
     * The capture shows the OEM putting the destination in rows 0 and 1, which the cluster draws
     * on the two bottom lines, and the turn instruction in row 2, which it draws as the banner
     * across the top. Writing only row 0 — which is what RideBuddy did — fills the bottom line and
     * leaves the banner empty, which is exactly how it looked on the bike.
     *
     * All three rows are always written so shorter replacement text clears what was there before.
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

    fun speedLimit(kph: Int): ByteArray = byteArrayOf(2, kph.coerceIn(0, 255).toByte(), End.toByte())
    fun clear(): ByteArray = byteArrayOf(0xFF.toByte(), End.toByte())
    fun session(state: Int): ByteArray = byteArrayOf(5, 0xFF.toByte(), state.coerceIn(0, 255).toByte(), End.toByte())
    fun status(code: Int): ByteArray = byteArrayOf(6, code.coerceIn(0, 255).toByte(), End.toByte())

    fun clusterManeuver(maneuver: Int, roundaboutExit: Int = 0): Int {
        if (roundaboutExit in 1..8) return 150 + roundaboutExit
        return when (maneuver) {
            Maneuver.TURN_RIGHT -> 1
            Maneuver.TURN_SLIGHT_RIGHT -> 3
            Maneuver.TURN_KEEP_RIGHT, Maneuver.FORK_RIGHT -> 2
            Maneuver.TURN_SHARP_RIGHT -> 4
            Maneuver.TURN_LEFT -> 6
            Maneuver.TURN_SLIGHT_LEFT -> 7
            Maneuver.TURN_KEEP_LEFT, Maneuver.FORK_LEFT -> 5
            Maneuver.TURN_SHARP_LEFT -> 10
            Maneuver.TURN_U_TURN_CLOCKWISE -> 12
            Maneuver.TURN_U_TURN_COUNTERCLOCKWISE -> 11
            Maneuver.MERGE_LEFT, Maneuver.MERGE_RIGHT, Maneuver.ON_RAMP_KEEP_LEFT, Maneuver.ON_RAMP_KEEP_RIGHT -> 8
            Maneuver.ON_RAMP_LEFT -> 6
            Maneuver.ON_RAMP_RIGHT -> 1
            Maneuver.OFF_RAMP_LEFT -> 13
            Maneuver.OFF_RAMP_RIGHT -> 14
            Maneuver.ROUNDABOUT_CLOCKWISE,
            Maneuver.ROUNDABOUT_STRAIGHT_CLOCKWISE,
            Maneuver.ROUNDABOUT_LEFT_CLOCKWISE,
            Maneuver.ROUNDABOUT_RIGHT_CLOCKWISE,
            Maneuver.ROUNDABOUT_SLIGHT_LEFT_CLOCKWISE,
            Maneuver.ROUNDABOUT_SLIGHT_RIGHT_CLOCKWISE,
            Maneuver.ROUNDABOUT_SHARP_LEFT_CLOCKWISE,
            Maneuver.ROUNDABOUT_SHARP_RIGHT_CLOCKWISE,
            Maneuver.ROUNDABOUT_U_TURN_CLOCKWISE,
            Maneuver.ROUNDABOUT_EXIT_CLOCKWISE,
            -> 157
            Maneuver.ROUNDABOUT_COUNTERCLOCKWISE,
            Maneuver.ROUNDABOUT_STRAIGHT_COUNTERCLOCKWISE,
            Maneuver.ROUNDABOUT_LEFT_COUNTERCLOCKWISE,
            Maneuver.ROUNDABOUT_RIGHT_COUNTERCLOCKWISE,
            Maneuver.ROUNDABOUT_SLIGHT_LEFT_COUNTERCLOCKWISE,
            Maneuver.ROUNDABOUT_SLIGHT_RIGHT_COUNTERCLOCKWISE,
            Maneuver.ROUNDABOUT_SHARP_LEFT_COUNTERCLOCKWISE,
            Maneuver.ROUNDABOUT_SHARP_RIGHT_COUNTERCLOCKWISE,
            Maneuver.ROUNDABOUT_U_TURN_COUNTERCLOCKWISE,
            Maneuver.ROUNDABOUT_EXIT_COUNTERCLOCKWISE,
            -> 158
            Maneuver.DESTINATION, Maneuver.DESTINATION_LEFT, Maneuver.DESTINATION_RIGHT -> 200
            Maneuver.FERRY_BOAT, Maneuver.FERRY_TRAIN -> 205
            Maneuver.STRAIGHT, Maneuver.DEPART, Maneuver.NAME_CHANGE -> 5
            else -> 201
        }
    }

    /**
     * The cluster reads these 24-bit fields least-significant byte first.
     *
     * The OEM builder makes this hard to see: `q(int)` renders the value as hex, chunks it into
     * bytes, reverses, and fills a three-slot array from index 2 downward, producing a
     * right-aligned *big-endian* array. Every packet builder then emits that array in reverse —
     * `iArr[5] = q[2]` for the maneuver distance, and the same pattern for both `8230` fields — so
     * the bytes that reach the wire are little-endian. See docs/aprilia-rs457-ble-protocol.md.
     */
    private const val MaxUInt24 = 0xFF_FFFF

    private fun ByteArray.writeUInt24LittleEndian(offset: Int, value: Int) {
        val safe = value.coerceIn(0, MaxUInt24)
        this[offset] = safe.toByte()
        this[offset + 1] = (safe ushr 8).toByte()
        this[offset + 2] = (safe ushr 16).toByte()
    }

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
