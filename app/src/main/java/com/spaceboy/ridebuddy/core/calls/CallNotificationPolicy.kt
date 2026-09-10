package com.spaceboy.ridebuddy.core.calls

import java.util.Locale

internal enum class CallActionKind { Answer, Decline, HangUp }

/** Exact legacy labels only: "call back" and "do not answer" must never answer a call. */
internal fun callActionKind(label: String?): CallActionKind? = when (
    label?.trim()?.lowercase(Locale.ROOT)?.replace(Regex("\\s+"), " ")
) {
    "answer", "answer call", "accept", "accept call", "pick up" -> CallActionKind.Answer
    "decline", "decline call", "reject", "reject call" -> CallActionKind.Decline
    "hang up", "hangup", "end", "end call", "disconnect" -> CallActionKind.HangUp
    else -> null
}

/** A dismissed/missed-call card must not resurrect a call on the motorcycle. */
internal fun isLiveCallNotification(
    hasCallStyle: Boolean,
    hasCallIntent: Boolean,
    isCallCategory: Boolean,
    isOngoing: Boolean,
    hasLegacyCallAction: Boolean,
    isKnownDialer: Boolean,
): Boolean = hasCallStyle || hasCallIntent ||
    (isCallCategory && (isOngoing || hasLegacyCallAction)) ||
    (isKnownDialer && hasLegacyCallAction)

/** Notification text can be a status or duration; extract digits only from a phone-shaped value. */
internal fun callerPhoneNumber(value: String?): String? {
    val text = value?.removePrefix("tel:")?.trim().orEmpty()
    if (text.isEmpty() || text.any { !it.isDigit() && it !in "+()- ." }) return null
    return text.filter { it.isDigit() || it == '+' }.takeIf { number ->
        number.count(Char::isDigit) >= 5 && number.drop(1).none { it == '+' }
    }
}
