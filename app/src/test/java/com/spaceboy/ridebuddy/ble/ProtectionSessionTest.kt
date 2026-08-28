package com.spaceboy.ridebuddy.ble

import com.spaceboy.ridebuddy.domain.ProtectionPath
import com.spaceboy.ridebuddy.domain.ProtectionPhase
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtectionSessionTest {
    @Test
    fun `fresh session begins by enabling challenge indications`() {
        val session = session(accepted = false)

        assertEquals(ProtectionAction.SubscribeChallenge, session.begin())
        assertEquals(ProtectionPhase.SubscribingChallenge, session.phase)
    }

    @Test
    fun `stored acceptance bypasses challenge subscription`() {
        val session = session(accepted = true)

        assertEquals(ProtectionAction.BeginPostAuthentication, session.begin())
        assertEquals(ProtectionPhase.Verifying, session.phase)
        assertEquals(ProtectionPath.StoredAcceptance, session.path)
    }

    @Test
    fun `fresh session awaits an indicated challenge after subscription`() {
        val session = session(accepted = false)
        session.begin()

        assertEquals(ProtectionAction.None, session.onChallengeSubscriptionReady())
        assertEquals(ProtectionPhase.AwaitingChallenge, session.phase)
    }

    @Test
    fun `known indicated challenge emits its response only once`() {
        val session = session(accepted = false)
        session.begin()
        val challenge = hex("63 75 A3 A4 63 3B")

        val first = session.onChallenge(challenge)
        val duplicate = session.onChallenge(challenge.copyOf())

        assertTrue(first is ProtectionAction.WriteResponse)
        assertArrayEquals(hex("E9 77 97 5C C3 45"), (first as ProtectionAction.WriteResponse).value)
        assertEquals(ProtectionAction.None, duplicate)
        assertEquals(ProtectionPath.ChallengeIndication, session.path)
        assertEquals(ProtectionPhase.Responding, session.phase)
    }

    @Test
    fun `unknown challenge fails without guessing`() {
        val session = session(accepted = false)
        session.begin()

        val action = session.onChallenge(ByteArray(6))

        assertTrue(action is ProtectionAction.Fail)
        action as ProtectionAction.Fail
        assertEquals(ProtectionFailurePolicy.ClearAcceptance, action.policy)
    }

    @Test
    fun `distinct known challenge is serialized behind a pending response`() {
        val session = session(accepted = false)
        session.begin()
        session.onChallenge(hex("63 75 A3 A4 63 3B"))

        val action = session.onChallenge(hex("D9 EA DE F2 F9 A1"))

        assertTrue(action is ProtectionAction.WriteResponse)
        action as ProtectionAction.WriteResponse
        assertArrayEquals(hex("95 C0 F8 B8 D7 AE"), action.value)
        assertEquals(ProtectionAction.None, session.onProtectionResponseWritten())
        assertEquals(ProtectionPhase.Responding, session.phase)
        assertEquals(ProtectionAction.BeginPostAuthentication, session.onProtectionResponseWritten())
    }

    @Test
    fun `response write starts verification and profile evidence completes authentication`() {
        val session = session(accepted = false)
        session.begin()
        session.onChallenge(hex("63 75 A3 A4 63 3B"))

        assertEquals(ProtectionAction.BeginPostAuthentication, session.onProtectionResponseWritten())
        val completed = session.onPostAuthenticationEvidence("valid telemetry")

        assertEquals(ProtectionAction.CompleteAuthentication("valid telemetry"), completed)
        assertEquals(ProtectionPhase.Ready, session.phase)
        assertEquals(ProtectionAction.None, session.onPostAuthenticationEvidence("VIN"))
    }

    @Test
    fun `verification timeout preserves acceptance and requests reconnect`() {
        val session = session(accepted = true)
        session.begin()

        val action = session.onVerificationTimeout()

        assertTrue(action is ProtectionAction.Fail)
        action as ProtectionAction.Fail
        assertEquals(ProtectionFailurePolicy.Reconnect, action.policy)
    }

    @Test
    fun `challenge timeout requests reconnect without treating transport loss as rejection`() {
        val session = session(accepted = false)
        session.begin()
        session.onChallengeSubscriptionReady()

        val action = session.onChallengeTimeout()

        assertTrue(action is ProtectionAction.Fail)
        action as ProtectionAction.Fail
        assertEquals(ProtectionFailurePolicy.Reconnect, action.policy)
    }

    @Test
    fun `challenge timeout is ignored after a response is selected`() {
        val session = session(accepted = false)
        session.begin()
        session.onChallengeSubscriptionReady()
        session.onChallenge(hex("63 75 A3 A4 63 3B"))

        assertEquals(ProtectionAction.None, session.onChallengeTimeout())
    }

    @Test
    fun `response completion without a challenge path restarts the link without discarding acceptance`() {
        val session = session(accepted = false)

        val action = session.onProtectionResponseWritten()

        assertTrue(action is ProtectionAction.Fail)
        action as ProtectionAction.Fail
        assertEquals(ProtectionFailurePolicy.Reconnect, action.policy)
        assertTrue(action.message.contains("without a pending challenge"))
    }

    @Test
    fun `challenge before the session began restarts the link without discarding acceptance`() {
        val session = session(accepted = false)

        val action = session.onChallenge(hex("63 75 A3 A4 63 3B"))

        assertTrue(action is ProtectionAction.Fail)
        action as ProtectionAction.Fail
        assertEquals(ProtectionFailurePolicy.Reconnect, action.policy)
    }

    @Test
    fun `begin is idempotent`() {
        val session = session(accepted = false)

        assertEquals(ProtectionAction.SubscribeChallenge, session.begin())
        assertEquals(ProtectionAction.None, session.begin())
    }

    /**
     * These two states were previously resumed from in place. They are unreachable on the
     * stored-acceptance path, which never subscribes to 8610, and unobserved on the fresh-pairing
     * one — so a challenge arriving there means our own bookkeeping is wrong, and the link is
     * restarted instead. Stored acceptance survives, which is what makes that cheap.
     */
    @Test
    fun `a challenge during verification restarts the link and keeps acceptance`() {
        val session = session(accepted = true)
        session.begin()

        val action = session.onChallenge(hex("63 75 A3 A4 63 3B"))

        assertTrue(action is ProtectionAction.Fail)
        action as ProtectionAction.Fail
        assertEquals(ProtectionFailurePolicy.Reconnect, action.policy)
    }

    @Test
    fun `a challenge after readiness restarts the link and keeps acceptance`() {
        val session = session(accepted = true)
        session.begin()
        session.onPostAuthenticationEvidence("VIN")
        assertEquals(ProtectionPhase.Ready, session.phase)

        val action = session.onChallenge(hex("63 75 A3 A4 63 3B"))

        assertTrue(action is ProtectionAction.Fail)
        action as ProtectionAction.Fail
        assertEquals(ProtectionFailurePolicy.Reconnect, action.policy)
    }

    @Test
    fun `required profile failure is terminal without clearing protection acceptance`() {
        val session = session(accepted = true)
        session.begin()

        val action = session.onRequiredProfileFailure("Could not enable required data")

        assertTrue(action is ProtectionAction.Fail)
        action as ProtectionAction.Fail
        assertEquals(ProtectionFailurePolicy.Stop, action.policy)
    }

    @Test
    fun `challenge callback before begin fails without mutating the state`() {
        val session = session(accepted = false)

        val action = session.onChallenge(hex("63 75 A3 A4 63 3B"))

        assertTrue(action is ProtectionAction.Fail)
        assertEquals(ProtectionPhase.Idle, session.phase)
    }

    private fun session(accepted: Boolean): ProtectionSession =
        ProtectionSession(previouslyAccepted = accepted)

    private fun hex(value: String): ByteArray = value.split(" ")
        .filter(String::isNotBlank)
        .map { part -> part.toInt(16).toByte() }
        .toByteArray()
}
