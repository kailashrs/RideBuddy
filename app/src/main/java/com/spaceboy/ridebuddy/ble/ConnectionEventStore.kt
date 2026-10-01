package com.spaceboy.ridebuddy.ble

import android.content.Context
import android.util.AtomicFile
import java.io.File

/**
 * Storage behind [ConnectionEventJournal]. [write] returns success rather than throwing so
 * the journal can keep the events pending and retry, instead of losing them.
 */
internal interface ConnectionEventStore {
    fun read(): List<String>
    fun write(events: List<String>): Boolean
}

/**
 * One event per line, newest first. Line breaks inside an event are flattened on the way in,
 * which is all it takes for a plain text file to hold them unambiguously.
 */
internal class FileConnectionEventStore(file: File) : ConnectionEventStore {
    private val file = AtomicFile(file)

    override fun read(): List<String> = runCatching {
        if (!file.baseFile.exists()) return emptyList()
        file.readFully().decodeToString().lines().filter(String::isNotEmpty).take(ConnectionEventLimit)
    }.getOrDefault(emptyList())

    override fun write(events: List<String>): Boolean = runCatching {
        val stream = file.startWrite()
        try {
            stream.write(events.take(ConnectionEventLimit).joinToString("\n") { it.replace('\n', ' ') }.encodeToByteArray())
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }.isSuccess

    companion object {
        fun create(context: Context) = FileConnectionEventStore(File(context.filesDir, "connection_journal.txt"))
    }
}
