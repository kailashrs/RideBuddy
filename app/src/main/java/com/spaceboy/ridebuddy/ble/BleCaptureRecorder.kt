package com.spaceboy.ridebuddy.ble

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

import kotlinx.coroutines.flow.update

/**
 * An opt-in, in-memory record of GATT traffic. Capture data can include identifiers and
 * notification text, so it is never written to disk and is cleared when the app process exits.
 */
class BleCaptureRecorder {
    private val mutableState = MutableStateFlow(BleCaptureState())
    val state: StateFlow<BleCaptureState> = mutableState.asStateFlow()

    fun setEnabled(enabled: Boolean) {
        mutableState.update { it.copy(enabled = enabled) }
    }

    fun clear() {
        mutableState.update { it.copy(entries = emptyList(), droppedEntries = 0) }
    }

    fun record(direction: BleCaptureDirection, characteristic: UUID, payload: ByteArray, outcome: String? = null) {
        mutableState.update { current ->
            if (!current.enabled) return@update current
            val entry = BleCaptureEntry(
                timestampMillis = System.currentTimeMillis(),
                direction = direction,
                characteristic = characteristic,
                payload = payload.copyOf(),
                outcome = outcome,
            )
            val entries = current.entries + entry
            val overflow = (entries.size - MaxEntries).coerceAtLeast(0)
            current.copy(
                entries = if (overflow == 0) entries else entries.drop(overflow),
                droppedEntries = current.droppedEntries + overflow,
            )
        }
    }

    fun exportText(): String = buildString {
        val current = mutableState.value
        appendLine("RideBuddy BLE capture")
        appendLine("Entries: ${current.entries.size}; dropped: ${current.droppedEntries}")
        appendLine("Payloads are raw GATT bytes and may contain personal data.")
        appendLine()
        current.entries.forEach { appendLine(it.format()) }
    }

    private companion object {
        const val MaxEntries = 500
    }
}

data class BleCaptureState(
    val enabled: Boolean = false,
    val entries: List<BleCaptureEntry> = emptyList(),
    val droppedEntries: Int = 0,
)

data class BleCaptureEntry(
    val timestampMillis: Long,
    val direction: BleCaptureDirection,
    val characteristic: UUID,
    val payload: ByteArray,
    val outcome: String?,
) {
    fun format(): String {
        val timestamp = TimestampFormatter.format(Instant.ofEpochMilli(timestampMillis))
        val bytes = payload.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
        return listOf(timestamp, direction.label, characteristic, bytes, outcome)
            .filterNotNull()
            .joinToString("  ")
    }
}

enum class BleCaptureDirection(val label: String) {
    Outbound("TX"),
    Notification("NTF"),
    Read("READ"),
}

private val TimestampFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault())
