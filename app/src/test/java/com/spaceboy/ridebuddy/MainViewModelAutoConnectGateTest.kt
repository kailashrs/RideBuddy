package com.spaceboy.ridebuddy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks in the one-shot semantics that the auto-connect gate relies on. The gate
 * runs on the main thread inside `MainActivity.onCreate`; the contract is simple
 * (first call → true, every subsequent call → false) and the test makes it
 * permanent.
 */
class MainViewModelAutoConnectGateTest {
    @Test
    fun firstConsumeReturnsTrueAndMarksAttempted() {
        val gate = MainViewModelAutoConnectGate()
        assertTrue(gate.consume())
        assertEquals(true, gate.attempted.value)
    }

    @Test
    fun everyConsumeAfterTheFirstReturnsFalse() {
        // Simulates the onCreate → onResume → onResume → … sequence: the first
        // resume after a fresh process gets the token, every subsequent resume
        // (screen unlock, permission dialog dismissal) must NOT re-trigger the
        // foreground service.
        val gate = MainViewModelAutoConnectGate()
        assertTrue(gate.consume())
        repeat(5) {
            assertFalse("call #$it after first consume must return false", gate.consume())
        }
        assertEquals(true, gate.attempted.value)
    }

    @Test
    fun freshGateResetsToFirstConsumeTrue() {
        // Each MainViewModel / MainActivity pair gets its own gate, so a fresh
        // process starts from clean state.
        val first = MainViewModelAutoConnectGate()
        assertTrue(first.consume())
        assertFalse(first.consume())

        val second = MainViewModelAutoConnectGate()
        assertTrue("fresh gate must hand out true again", second.consume())
    }

    @Test
    fun attemptedFlowEmitsAfterFirstConsume() {
        // Subscribers (e.g. future diagnostics) see the attempted flag flip
        // immediately. MutableStateFlow.value is the synchronous accessor and is
        // sufficient here because the test is single-threaded.
        val gate = MainViewModelAutoConnectGate()
        assertEquals(false, gate.attempted.value)
        gate.consume()
        assertEquals(true, gate.attempted.value)
    }
}
