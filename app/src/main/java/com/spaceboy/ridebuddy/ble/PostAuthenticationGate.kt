package com.spaceboy.ridebuddy.ble

import java.util.UUID

internal data class SubscriptionGateUpdate(
    val becameReady: Boolean,
    val deferredEvidence: String? = null,
)

/**
 * Prevents an early notification from making a partially subscribed profile appear ready.
 * RideBuddy enables the same normal notification set as the India OEM path, then additionally
 * verifies that every required CCCD write completed before exposing the link as ready.
 */
internal class PostAuthenticationGate(requiredSubscriptions: Collection<UUID>) {
    private val pending = requiredSubscriptions.toMutableSet()
    private var deferredEvidence: String? = null

    private val isReady: Boolean
        get() = pending.isEmpty()

    fun acceptEvidence(evidence: String): String? = if (isReady) {
        evidence
    } else {
        if (deferredEvidence == null) deferredEvidence = evidence
        null
    }

    fun markSubscriptionEnabled(uuid: UUID): SubscriptionGateUpdate {
        if (!pending.remove(uuid)) return SubscriptionGateUpdate(becameReady = false)
        if (!isReady) return SubscriptionGateUpdate(becameReady = false)
        return SubscriptionGateUpdate(
            becameReady = true,
            deferredEvidence = deferredEvidence.also { deferredEvidence = null },
        )
    }
}
