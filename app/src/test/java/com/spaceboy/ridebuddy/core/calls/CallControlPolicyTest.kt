package com.spaceboy.ridebuddy.core.calls

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class CallControlPolicyTest {
    @Test
    fun controlsPublishCallStateWhenCallerDisplayIsDisabled() {
        assertTrue(shouldPublishCallState(callerDisplay = false, tftCallControls = true))
    }

    @Test
    fun callStateIsSilentOnlyWhenBothCallFeaturesAreDisabled() {
        assertFalse(shouldPublishCallState(callerDisplay = false, tftCallControls = false))
        assertTrue(shouldPublishCallState(callerDisplay = true, tftCallControls = false))
    }

    @Test
    fun disablingBothFeaturesClearsOnlyAPreviouslyPublishedCall() {
        assertTrue(shouldClearPublishedCall(true, callerDisplay = false, tftCallControls = false))
        assertFalse(shouldClearPublishedCall(false, callerDisplay = false, tftCallControls = false))
        assertFalse(shouldClearPublishedCall(true, callerDisplay = false, tftCallControls = true))
    }

    @Test
    fun legacyFallbackRequiresExplicitSettingAndPermission() {
        assertFalse(canUseLegacyCallFallback(enabled = false, permissionGranted = true))
        assertFalse(canUseLegacyCallFallback(enabled = true, permissionGranted = false))
        assertTrue(canUseLegacyCallFallback(enabled = true, permissionGranted = true))
    }

    /**
     * Android publishes an answered incoming call and an outgoing one identically, so the only
     * thing separating them is whether the key was already on record as ringing.
     */
    @Test
    fun `an incoming ring is reported as ringing`() {
        assertEquals(
            TftCallState.Ringing,
            tftCallStateFor(callStyleIncoming = true, hasAnswerIntent = false, previousState = null),
        )
    }

    @Test
    fun `an answer intent alone still means the call is ringing`() {
        assertEquals(
            TftCallState.Ringing,
            tftCallStateFor(
                callStyleIncoming = false,
                hasAnswerIntent = true,
                previousState = TftCallState.Ringing,
            ),
        )
    }

    @Test
    fun `a call that was ringing here and stops ringing has been answered`() {
        assertEquals(
            TftCallState.Answered,
            tftCallStateFor(
                callStyleIncoming = false,
                hasAnswerIntent = false,
                previousState = TftCallState.Ringing,
            ),
        )
    }

    @Test
    fun `a call first seen already in progress was dialled from this phone`() {
        assertEquals(
            TftCallState.Outgoing,
            tftCallStateFor(callStyleIncoming = false, hasAnswerIntent = false, previousState = null),
        )
    }

    /**
     * Android reposts the same notification key as a call runs — the duration ticks, the audio
     * route changes. Every repost used to be read as an answer, so an outgoing call flipped to
     * "Answered" on its first update whether or not anyone had picked up.
     */
    @Test
    fun `an outgoing call stays outgoing when its notification is updated`() {
        assertEquals(
            TftCallState.Outgoing,
            tftCallStateFor(
                callStyleIncoming = false,
                hasAnswerIntent = false,
                previousState = TftCallState.Outgoing,
            ),
        )
    }

    @Test
    fun `an answered call stays answered across further updates`() {
        assertEquals(
            TftCallState.Answered,
            tftCallStateFor(
                callStyleIncoming = false,
                hasAnswerIntent = false,
                previousState = TftCallState.Answered,
            ),
        )
    }

    /**
     * `8740` is not call-only, and the OEM switches on the whole value rendered as a decimal
     * string. 3 was reaching the log as unhandled: it is the cluster asserting a call is live,
     * which in the OEM is what arms its answer and reject handling.
     */
    @Test
    fun `the cluster control vocabulary covers all four values`() {
        assertEquals("reject", callControlLabel(0))
        assertEquals("answer", callControlLabel(1))
        assertEquals("cluster ready", callControlLabel(2))
        assertEquals("call active", callControlLabel(3))
        assertEquals(null, callControlLabel(4))
    }
}

/** Mirrors the read in AndroidBikeConnection.onNotification for CallControl. */
private fun callControlLabel(value: Int): String? = when (value) {
    0 -> "reject"
    1 -> "answer"
    2 -> "cluster ready"
    3 -> "call active"
    else -> null
}
