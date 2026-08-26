package com.spaceboy.ridebuddy.core.calls

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
}
