package com.spaceboy.ridebuddy.core.navigation

/**
 * Local sanity checks on a Navigation SDK key before it is stored.
 *
 * These catch the mistakes that are obvious without a network round trip — an empty field,
 * a truncated paste, a copy that dragged in surrounding whitespace. Whether the key is
 * actually valid and correctly restricted is decided by the SDK when it is applied.
 */
object NavigationApiKeyPolicy {
    /** Comfortably shorter than a real key, so this rejects truncation but not format drift. */
    private const val MinimumLength = 20

    /** The problem to show the rider, or null when the key looks plausible. */
    fun validate(value: String): String? {
        val key = value.trim()
        return when {
            key.isEmpty() -> "Enter a Google Navigation API key"
            key.length < MinimumLength -> "The API key looks too short"
            key.any(Char::isWhitespace) -> "The API key cannot contain spaces"
            else -> null
        }
    }

    /**
     * Display form. Only the last four characters are shown — enough for the rider to tell
     * which key is stored, without putting the key itself on screen or into a screenshot.
     */
    fun mask(value: String): String = "•••• ${value.takeLast(4)}"
}
