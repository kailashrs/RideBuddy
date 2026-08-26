package com.spaceboy.ridebuddy.ble

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * An opt-in, in-memory record of GATT traffic. Capture data can include identifiers and
 * notification text, so it is never written to disk and is cleared when the app process exits.
 */
class BleCaptureRecorder internal constructor(
    private val scope: CoroutineScope? = null,
    private val publishIntervalMillis: Long = DefaultPublishIntervalMillis,
) {
    private val lock = Any()
    private val entries = ArrayDeque<BleCaptureEntry>(MaxEntries)
    private val mutableState = MutableStateFlow(BleCaptureState())
    private var enabled = false
    private var droppedEntries = 0
    private var publishJob: Job? = null
    val state: StateFlow<BleCaptureState> = mutableState.asStateFlow()

    fun setEnabled(enabled: Boolean) {
        val snapshot = synchronized(lock) {
            this.enabled = enabled
            snapshotLocked()
        }
        mutableState.value = snapshot
    }

    fun clear() {
        val snapshot = synchronized(lock) {
            entries.clear()
            droppedEntries = 0
            snapshotLocked()
        }
        mutableState.value = snapshot
    }

    fun record(direction: BleCaptureDirection, characteristic: UUID, payload: ByteArray, outcome: String? = null) {
        val immediateSnapshot = synchronized(lock) {
            if (!enabled) return
            val entry = BleCaptureEntry(
                timestampMillis = System.currentTimeMillis(),
                direction = direction,
                characteristic = characteristic,
                payload = payload.copyOf(),
                outcome = outcome,
            )
            if (entries.size == MaxEntries) {
                entries.removeFirst()
                droppedEntries++
            }
            entries.addLast(entry)
            scheduleSnapshotLocked()
        }
        immediateSnapshot?.let { mutableState.value = it }
    }

    fun exportText(): String = buildString {
        val current = synchronized(lock) { snapshotLocked() }
        appendLine("RideBuddy BLE capture")
        appendLine("Entries: ${current.entries.size}; dropped: ${current.droppedEntries}")
        appendLine("Payloads are raw GATT bytes and may contain personal data.")
        appendLine()
        current.entries.forEach { appendLine(it.format()) }
    }

    private fun scheduleSnapshotLocked(): BleCaptureState? {
        val publisher = scope
        if (publisher == null || publishIntervalMillis <= 0L) return snapshotLocked()
        if (publishJob?.isActive != true) {
            publishJob = publisher.launch {
                delay(publishIntervalMillis)
                publishSnapshot()
            }
        }
        return null
    }

    private fun publishSnapshot() {
        val snapshot = synchronized(lock) {
            publishJob = null
            snapshotLocked()
        }
        mutableState.value = snapshot
    }

    private fun snapshotLocked(): BleCaptureState = BleCaptureState(
        enabled = enabled,
        entries = entries.toList(),
        droppedEntries = droppedEntries,
    )

    private companion object {
        const val MaxEntries = 500
        const val DefaultPublishIntervalMillis = 250L
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
        return listOfNotNull(timestamp, direction.label, characteristic, bytes, outcome)
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
