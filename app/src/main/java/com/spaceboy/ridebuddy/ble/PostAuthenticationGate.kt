package com.spaceboy.ridebuddy.ble

import java.util.UUID

/**
 * Result of enabling one subscription.
 *
 * [becameReady] is true exactly once per session — on the write that completes the last
 * outstanding subscription. [deferredEvidence] carries whatever profile evidence arrived
 * early and was held back, so the caller can act on it at the moment readiness is
 * declared instead of losing it.
 */
internal data class SubscriptionGateUpdate(
    val becameReady: Boolean,
    val deferredEvidence: String? = null,
)

/**
 * Holds a link back from "ready" until every required subscription is actually in place.
 *
 * The cluster starts pushing notifications as soon as the first CCCD write lands. Without
 * this gate, that first notification would be taken as proof the profile is up, and the
 * session would be presented as connected while later subscriptions were still pending —
 * or still failing. Instead, early evidence is stashed and replayed once the last
 * subscription completes.
 */
internal class PostAuthenticationGate(requiredSubscriptions: Collection<UUID>) {
    private val pending = requiredSubscriptions.toMutableSet()
    private var deferredEvidence: String? = null

    private val isReady: Boolean
        get() = pending.isEmpty()

    /**
     * Returns [evidence] if the profile is already fully subscribed, or null if it is
     * being held. Only the first held item is kept: it is the earliest proof the peer is
     * live, and that is all the caller needs.
     */
    fun acceptEvidence(evidence: String): String? = if (isReady) {
        evidence
    } else {
        if (deferredEvidence == null) deferredEvidence = evidence
        null
    }

    /**
     * Records that one subscription is now enabled. A UUID not in the required set, or
     * already seen, is ignored so a duplicate callback cannot declare readiness twice.
     */
    fun markSubscriptionEnabled(uuid: UUID): SubscriptionGateUpdate {
        if (!pending.remove(uuid)) return SubscriptionGateUpdate(becameReady = false)
        if (!isReady) return SubscriptionGateUpdate(becameReady = false)
        return SubscriptionGateUpdate(
            becameReady = true,
            deferredEvidence = deferredEvidence.also { deferredEvidence = null },
        )
    }
}
