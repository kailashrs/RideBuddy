package com.spaceboy.ridebuddy.ble

import android.annotation.SuppressLint
import android.content.Context

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

internal fun encodeConnectionEvents(events: List<String>): String = buildString {
    append('[')
    events.forEachIndexed { index, event ->
        if (index > 0) append(',')
        appendJsonString(event)
    }
    append(']')
}

internal fun decodeConnectionEvents(encoded: String): List<String> {
    if (encoded.isBlank()) return emptyList()
    return runCatching { JsonStringArrayParser(encoded).parse() }.getOrDefault(emptyList())
}

private fun StringBuilder.appendJsonString(value: String) {
    append('"')
    value.forEach { character ->
        when (character) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (character.code < 0x20 || character.isSurrogate()) {
                append("\\u")
                append(character.code.toString(16).padStart(4, '0'))
            } else {
                append(character)
            }
        }
    }
    append('"')
}

private class JsonStringArrayParser(private val input: String) {
    private var index = 0

    fun parse(): List<String> {
        skipWhitespace()
        expect('[')
        skipWhitespace()
        if (consume(']')) {
            requireEnd()
            return emptyList()
        }

        val values = mutableListOf<String>()
        while (true) {
            skipWhitespace()
            values += parseString()
            skipWhitespace()
            when {
                consume(',') -> Unit
                consume(']') -> {
                    requireEnd()
                    return values
                }

                else -> error("Expected ',' or ']' at position $index")
            }
        }
    }

    private fun parseString(): String {
        expect('"')
        return buildString {
            while (index < input.length) {
                when (val character = input[index++]) {
                    '"' -> return@buildString
                    '\\' -> appendEscapedCharacter()
                    else -> {
                        require(character.code >= 0x20) { "Unescaped control character at position ${index - 1}" }
                        append(character)
                    }
                }
            }
            error("Unterminated JSON string")
        }
    }

    private fun StringBuilder.appendEscapedCharacter() {
        require(index < input.length) { "Incomplete JSON escape" }
        when (val escaped = input[index++]) {
            '"', '\\', '/' -> append(escaped)
            'b' -> append('\b')
            'f' -> append('\u000C')
            'n' -> append('\n')
            'r' -> append('\r')
            't' -> append('\t')
            'u' -> append(parseUnicodeEscape())
            else -> error("Unsupported JSON escape \\$escaped")
        }
    }

    private fun parseUnicodeEscape(): Char {
        require(index + UnicodeEscapeLength <= input.length) { "Incomplete Unicode escape" }
        val value = input.substring(index, index + UnicodeEscapeLength).toIntOrNull(16)
            ?: error("Invalid Unicode escape at position $index")
        index += UnicodeEscapeLength
        return value.toChar()
    }

    private fun consume(expected: Char): Boolean {
        if (input.getOrNull(index) != expected) return false
        index++
        return true
    }

    private fun expect(expected: Char) {
        require(consume(expected)) { "Expected '$expected' at position $index" }
    }

    private fun requireEnd() {
        skipWhitespace()
        require(index == input.length) { "Unexpected trailing JSON data at position $index" }
    }

    private fun skipWhitespace() {
        while (input.getOrNull(index)?.isWhitespace() == true) index++
    }

    private companion object {
        const val UnicodeEscapeLength = 4
    }
}
