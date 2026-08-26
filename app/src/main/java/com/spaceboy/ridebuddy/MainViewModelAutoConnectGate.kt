package com.spaceboy.ridebuddy

import java.util.concurrent.atomic.AtomicBoolean

/**
 * One-shot guard for auto-connect on app start.
 *
 * `MainActivity.onCreate` calls [consume] exactly once after a fresh process; the
 * gate hands out a `true` token on the first call and `false` on every subsequent
 * call so the foreground service is never re-kicked off after a deliberate
 * disconnect, a 6-attempt backoff exhaustion, or any other subsequent resume.
 *
 * Extracted from [MainViewModel] into its own class so the gate can be exercised
 * in plain JUnit (without Robolectric or a full ViewModel construction) and so
 * the `MainActivity.onCreate` invocation site stays a single-line call.
 */
internal class MainViewModelAutoConnectGate {
    private val attempted = AtomicBoolean(false)

    /**
     * Returns true on the first call per gate lifetime, false thereafter.
     *
     * Thread-safe via [AtomicBoolean.compareAndSet]; callers may invoke this
     * from `MainActivity.onCreate` without holding the main-thread lock.
     */
    fun consume(): Boolean = attempted.compareAndSet(false, true)
}
