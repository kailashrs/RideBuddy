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
            tftCallStateFor(callStyleIncoming = true, hasAnswerIntent = false, keyWasAlreadyTracked = false),
        )
    }

    @Test
    fun `an answer intent alone still means the call is ringing`() {
        assertEquals(
            TftCallState.Ringing,
            tftCallStateFor(callStyleIncoming = false, hasAnswerIntent = true, keyWasAlreadyTracked = true),
        )
    }

    @Test
    fun `a tracked key that stops ringing has been answered`() {
        assertEquals(
            TftCallState.Answered,
            tftCallStateFor(callStyleIncoming = false, hasAnswerIntent = false, keyWasAlreadyTracked = true),
        )
    }

    @Test
    fun `a call first seen already in progress was dialled from this phone`() {
        assertEquals(
            TftCallState.Outgoing,
            tftCallStateFor(callStyleIncoming = false, hasAnswerIntent = false, keyWasAlreadyTracked = false),
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
