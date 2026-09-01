package com.spaceboy.ridebuddy.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import java.lang.ref.WeakReference

/**
 * One transport instance and the state that must be scoped to it.
 *
 * Android keeps delivering callbacks from a [BluetoothGatt] after the app has stopped using it, so
 * ownership cannot be a nullable field: the retired instance still needs an identity, and it must
 * be closed exactly once. [close] is idempotent and reports whether it did the work.
 *
 * The transport is a type parameter rather than [BluetoothGatt] so the lifetime rules can be
 * exercised without the Android framework.
 */
internal class GattSession<T : Any>(
    val id: Long,
    private val transport: T,
    val openedAtElapsedRealtime: Long,
    private val closeTransport: (transport: T, disconnectFirst: Boolean) -> Unit,
) {
    private var closed = false

    /** When the transport reported STATE_CONNECTED, for link-age reporting. */
    var connectedAtElapsedRealtime: Long? = null
        private set

    /**
     * Whether any ATT operation completed on this transport.
     *
     * Reported in the journal, never acted on. A link that dropped before the first operation may
     * have failed to encrypt, or the motorcycle may have gone away during setup — the disconnect
     * status is identical either way, and only an HCI capture separates them. Recording it makes
     * that distinction possible after the fact without the app pretending it can make it live.
     */
    var completedAnyOperation = false
        private set

    fun markOperationCompleted() { completedAnyOperation = true }

    fun owns(candidate: T?): Boolean = candidate === transport

    /**
     * A weak handle on the transport, for registry bookkeeping only. It is deliberately weak: the
     * registry has to recognise this instance for as long as it can still deliver a callback, and
     * that is exactly as long as the framework keeps it reachable. Handing out a strong reference
     * instead would keep every retired `BluetoothGatt` alive for the life of the process.
     */
    internal fun weakTransport(): WeakReference<T> = WeakReference(transport)

    /** The live transport, or null once the session has been retired. */
    fun openTransport(): T? = if (closed) null else transport

    fun markConnected(elapsedRealtime: Long) {
        if (connectedAtElapsedRealtime == null) connectedAtElapsedRealtime = elapsedRealtime
    }

    /** Milliseconds since the transport connected, or null when it never did. */
    fun linkAgeMillis(elapsedRealtime: Long): Long? =
        connectedAtElapsedRealtime?.let { connectedAt -> (elapsedRealtime - connectedAt).coerceAtLeast(0L) }

    /** Closes the transport at most once. Returns true only for the call that did the closing. */
    fun close(disconnectFirst: Boolean): Boolean {
        if (closed) return false
        closed = true
        closeTransport(transport, disconnectFirst)
        return true
    }
}

/**
 * Tracks the live session and every retired one that can still deliver a callback.
 *
 * The history is what lets a late callback be recognised as belonging to an already-closed session
 * instead of being closed a second time. It is kept weakly rather than capped at a fixed length: a
 * capped history evicted transports that were still able to call back, and the fallback for an
 * unrecognised instance is to close it — which is precisely the second close the guarantee forbids.
 * A weak entry survives for as long as the transport itself does, and no longer.
 */
internal class GattSessionRegistry<T : Any>(
    private val closeTransport: (transport: T, disconnectFirst: Boolean) -> Unit,
) {
    private var nextId = 0L
    private var activeSession: GattSession<T>? = null
    private val retiredTransports = mutableListOf<WeakReference<T>>()

    fun current(): GattSession<T>? = activeSession

    /** Retires any live session and adopts [transport] as the new one. */
    fun open(transport: T, openedAtElapsedRealtime: Long): GattSession<T> {
        retireCurrent(disconnectFirst = false)
        return GattSession(++nextId, transport, openedAtElapsedRealtime, closeTransport)
            .also { session -> activeSession = session }
    }

    /** Closes the live session once and moves it into the retired history. */
    fun retireCurrent(disconnectFirst: Boolean): GattSession<T>? {
        val session = activeSession ?: return null
        activeSession = null
        session.close(disconnectFirst)
        remember(session)
        return session
    }

    fun isCurrent(transport: T): Boolean = activeSession?.owns(transport) == true

    /** True when the callback came from a session this registry has already closed. */
    fun isRetired(transport: T): Boolean {
        forgetCollectedTransports()
        return retiredTransports.any { retired -> retired.get() === transport }
    }

    /**
     * Closes a transport that was created but never adopted, for example one whose connection
     * generation was superseded before it could become current.
     */
    fun closeUnadopted(transport: T, openedAtElapsedRealtime: Long) {
        val session = GattSession(++nextId, transport, openedAtElapsedRealtime, closeTransport)
        session.close(disconnectFirst = false)
        remember(session)
    }

    private fun remember(session: GattSession<T>) {
        forgetCollectedTransports()
        retiredTransports += session.weakTransport()
    }

    /** A transport the garbage collector has taken cannot deliver another callback. */
    private fun forgetCollectedTransports() {
        retiredTransports.removeAll { retired -> retired.get() == null }
    }
}

/**
 * Retires an Android GATT instance. `disconnect()` is only meaningful while the app still intends
 * a graceful teardown; after a link failure the instance is closed directly.
 */
@SuppressLint("MissingPermission")
internal fun closeAndroidGatt(gatt: BluetoothGatt, disconnectFirst: Boolean) {
    if (disconnectFirst) runCatching { gatt.disconnect() }
    runCatching { gatt.close() }
}
