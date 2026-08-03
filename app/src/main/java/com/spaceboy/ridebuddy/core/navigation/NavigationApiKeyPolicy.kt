package com.spaceboy.ridebuddy.core.navigation

object NavigationApiKeyPolicy {
    private const val MinimumLength = 20

    fun validate(value: String): String? {
        val key = value.trim()
        return when {
            key.isEmpty() -> "Enter a Google Navigation API key"
            key.length < MinimumLength -> "The API key looks too short"
            key.any(Char::isWhitespace) -> "The API key cannot contain spaces"
            else -> null
        }
    }

    fun mask(value: String): String = "•••• ${value.takeLast(4)}"
}
