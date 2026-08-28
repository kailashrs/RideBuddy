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
}
