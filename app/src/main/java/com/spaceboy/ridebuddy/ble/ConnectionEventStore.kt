package com.spaceboy.ridebuddy.ble

import android.annotation.SuppressLint
import android.content.Context
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Storage behind [ConnectionEventJournal]. [write] returns success rather than throwing so
 * the journal can keep the events pending and retry, instead of losing them.
 */
internal interface ConnectionEventStore {
    fun read(): List<String>
    fun write(events: List<String>): Boolean
}

internal class SharedPreferencesConnectionEventStore(
    context: Context,
    preferencesName: String = PreferencesName,
) : ConnectionEventStore {
    private val appContext = context.applicationContext
    private val preferences by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        appContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    }

    override fun read(): List<String> = runCatching {
        decodeConnectionEvents(preferences.getString(KeyEvents, null).orEmpty())
            .take(ConnectionEventLimit)
    }.getOrDefault(emptyList())

    /**
     * Called only by [ConnectionEventJournal]'s IO writer. The explicit editor API preserves
     * [android.content.SharedPreferences.Editor.commit]'s failure result and avoids lifecycle-bound
     * apply work.
     */
    @SuppressLint("ApplySharedPref", "UseKtx")
    override fun write(events: List<String>): Boolean = runCatching {
        preferences.edit()
            .putString(KeyEvents, encodeConnectionEvents(events.take(ConnectionEventLimit)))
            .commit()
    }.getOrDefault(false)

    private companion object {
        const val PreferencesName = "connection_diagnostics"
        const val KeyEvents = "recent_events"
    }
}

// The events are free-form log lines that may contain any character, so they cannot be stored with
// a plain delimiter — one appearing inside a message would split it. Each event is percent-encoded
// instead and the results joined with newlines: the encoding emits only ASCII, so no control
// character, newline or lone surrogate can reach the preferences XML and be mangled by it.
//
// This replaced a hand-written JSON encoder and parser. The parser existed only to read what the
// encoder in the same file had written, and its error paths were unreachable for that reason —
// while `decode` already treats anything it cannot read as an empty journal.

/** Percent-encoded events, newline separated. */
internal fun encodeConnectionEvents(events: List<String>): String =
    events.joinToString("\n") { event -> URLEncoder.encode(event, Charsets.UTF_8.name()) }

/** Malformed stored data yields an empty journal; diagnostics history is never worth a crash. */
internal fun decodeConnectionEvents(encoded: String): List<String> {
    if (encoded.isBlank()) return emptyList()
    return runCatching {
        encoded.split('\n')
            .filter(String::isNotEmpty)
            .map { event -> URLDecoder.decode(event, Charsets.UTF_8.name()) }
    }.getOrDefault(emptyList())
}
