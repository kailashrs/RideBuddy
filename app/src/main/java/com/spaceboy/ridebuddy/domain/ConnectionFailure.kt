package com.spaceboy.ridebuddy.domain

/** Why a connection attempt was started, so diagnostics never conflate the retry paths. */
enum class ConnectionAttemptTrigger {
    /** The user asked for this connection, directly or through the onboarding flow. */
    UserRequest,

    /** A companion BLE_APPEARED edge asked for a fresh attempt. */
    PresenceAppearance,

    /**
     * The one automatic attempt an app launch is allowed to make. No BLE appearance was involved,
     * so it must not be reported as one.
     */
    AppLaunch,

    /** The bounded backoff schedule owned by the connection controller. */
    AutomaticReconnect,

    /** A bond completed and the controller resumed the pending attempt. */
    BondCompleted,
}

/**
 * What kind of failure the stack observed. The category, not the message text, decides whether a
 * failure may clear stored protection acceptance or claim that authentication was rejected.
 */
enum class ConnectionFailureCategory {
    /** The link died or its state became unknowable; the GATT session must be retired. */
    LinkLost,

    /** The peer rejected the operation for a reason that cannot change on retry. */
    Deterministic,

    /** Controller congestion or a busy attribute server; the same link may retry shortly. */
    Transient,

    /** The peer or the bond rejected authentication. Only security statuses reach this. */
    AuthenticationRejected,

    /**
     * The motorcycle accepts the connection but never completes encryption with the stored
     * bonding key, so the link dies on the supervision timeout without a single ATT operation.
     * Retrying cannot resolve it: the phone has no way to present a different key.
     */
    PairingRejected,

    /** The phone blocked the attempt: permissions, adapter state, or a missing profile. */
    LocalPrecondition,

    /** The framework gave no usable reason. */
    Unknown,
}

/** Bond state captured at the moment of failure rather than re-read later. */
enum class BondStateSnapshot {
    None,
    Bonding,
    Bonded,
    Unknown,
}

/** What the last link that reached an authenticated session actually negotiated. */
data class LinkSnapshot(
    val sessionId: Long,
    val attMtu: Int? = null,
    val servicesDiscovered: Int = 0,
    val serviceSnapshot: List<String> = emptyList(),
    val establishedAtMillis: Long? = null,
    val durationMillis: Long? = null,
)

/** Everything known about the attempt a failure happened in, captured when the failure is built. */
data class ConnectionAttemptContext(
    val sessionId: Long? = null,
    val trigger: ConnectionAttemptTrigger? = null,
    val reconnectAttempt: Int = 0,
    val linkAgeMillis: Long? = null,
    val bondState: BondStateSnapshot = BondStateSnapshot.Unknown,
)

/**
 * A structured connection failure. [message] stays human-readable for the UI; every other field
 * exists so a diagnostics export can answer "which attempt, how far in, and against what link".
 */
data class ConnectionFailure(
    val message: String,
    val category: ConnectionFailureCategory,
    val atMillis: Long,
    val statusCode: Int? = null,
    val statusName: String? = null,
    val operation: String? = null,
    val operationDurationMillis: Long? = null,
    val context: ConnectionAttemptContext = ConnectionAttemptContext(),
) {
    /** One line of the structured context, for diagnostics exports and the event journal. */
    fun contextLine(): String = buildList {
        context.trigger?.let { add("trigger=$it") }
        context.sessionId?.let { add("session=$it") }
        if (context.reconnectAttempt > 0) add("retry=${context.reconnectAttempt}")
        operation?.let { add("operation=$it") }
        operationDurationMillis?.let { add("operationMs=$it") }
        context.linkAgeMillis?.let { add("linkAgeMs=$it") }
        add("bond=${context.bondState}")
        add("category=$category")
        statusName?.let { name -> add("status=$name(${statusCode ?: "?"})") }
    }.joinToString(", ")
}
