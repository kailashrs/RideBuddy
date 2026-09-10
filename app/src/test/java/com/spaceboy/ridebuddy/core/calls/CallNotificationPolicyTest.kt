package com.spaceboy.ridebuddy.core.calls

import org.junit.Assert.*
import org.junit.Test

class CallNotificationPolicyTest {
    @Test fun `live CallStyle does not depend on dialer package or English action labels`() {
        assertTrue(isLiveCallNotification(true, false, false, false, false, false))
        assertTrue(isLiveCallNotification(false, true, false, false, false, false))
    }

    @Test fun `legacy Truecaller actions work without the optional call category`() {
        assertTrue(isLiveCallNotification(false, false, false, false, true, true))
        assertFalse(isLiveCallNotification(false, false, false, false, true, false))
    }

    @Test fun `missed call without live state or controls does not reopen call screen`() {
        assertFalse(isLiveCallNotification(false, false, true, false, false, true))
        assertTrue(isLiveCallNotification(false, false, true, true, false, true))
    }

    @Test fun `legacy action labels match controls without accepting unrelated text`() {
        assertEquals(CallActionKind.Answer, callActionKind("  ANSWER   CALL "))
        assertEquals(CallActionKind.Decline, callActionKind("Decline"))
        assertEquals(CallActionKind.HangUp, callActionKind("End call"))
        assertNull(callActionKind("Do not answer"))
        assertNull(callActionKind("Call back"))
        assertNull(callActionKind("Send message"))
    }

    @Test fun `caller number accepts phone formatting and rejects notification status digits`() {
        assertEquals("+919876543210", callerPhoneNumber("tel:+91 (98765) 43210"))
        assertNull(callerPhoneNumber("Duration 12:34:56"))
        assertNull(callerPhoneNumber("Unknown caller 12345"))
        assertNull(callerPhoneNumber("123+456"))
        assertNull(callerPhoneNumber("content://contacts/123456"))
    }
}
