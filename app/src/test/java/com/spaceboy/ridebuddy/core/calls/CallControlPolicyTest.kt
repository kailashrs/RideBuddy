package com.spaceboy.ridebuddy.core.calls

import android.telecom.Call

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun `a ringing call is ringing whichever way it rang`() {
        assertEquals(TftCallState.Ringing, tftCallStateForTelecom(Call.STATE_RINGING, incoming = true))
        assertEquals(
            TftCallState.Ringing,
            tftCallStateForTelecom(Call.STATE_SIMULATED_RINGING, incoming = true),
        )
    }

    /**
     * The distinction notifications could not make. Telecom states it outright, so an
     * outgoing call that connects never reads as "answered" on the cluster.
     */
    @Test
    fun `only an incoming call becomes answered once it is active`() {
        assertEquals(TftCallState.Answered, tftCallStateForTelecom(Call.STATE_ACTIVE, incoming = true))
        assertEquals(TftCallState.Outgoing, tftCallStateForTelecom(Call.STATE_ACTIVE, incoming = false))
        assertEquals(TftCallState.Answered, tftCallStateForTelecom(Call.STATE_HOLDING, incoming = true))
    }

    @Test
    fun `a call being dialled reads as outgoing`() {
        assertEquals(TftCallState.Outgoing, tftCallStateForTelecom(Call.STATE_DIALING, incoming = false))
        assertEquals(TftCallState.Outgoing, tftCallStateForTelecom(Call.STATE_CONNECTING, incoming = false))
    }

    /** Nothing the rider should see: not yet a call, already over, or being screened. */
    @Test
    fun `states with nothing to show produce no call`() {
        assertNull(tftCallStateForTelecom(Call.STATE_NEW, incoming = true))
        assertNull(tftCallStateForTelecom(Call.STATE_DISCONNECTED, incoming = true))
        assertNull(tftCallStateForTelecom(Call.STATE_DISCONNECTING, incoming = true))
        assertNull(tftCallStateForTelecom(Call.STATE_AUDIO_PROCESSING, incoming = true))
        assertNull(tftCallStateForTelecom(Call.STATE_SELECT_PHONE_ACCOUNT, incoming = true))
    }

    @Test
    fun `only a tel handle yields a dialable number`() {
        assertEquals("+919876543210", telecomCallerNumber("tel", "+919876543210"))
        // A SIP or app call has a handle, but not a phone number.
        assertNull(telecomCallerNumber("sip", "someone@example.com"))
        // A withheld number arrives as an empty handle.
        assertNull(telecomCallerNumber("tel", ""))
        assertNull(telecomCallerNumber(null, null))
    }
}
