package com.spaceboy.ridebuddy.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Retains completed rides until the transactional insert succeeds, including after a disk error. */
internal class RideSaveQueue(
    private val insert: suspend (Ride, List<RideSample>) -> Long,
    private val onSaved: (Long, Ride) -> Unit,
    private val onFailure: (Exception) -> Unit,
) {
    private data class PendingRide(val ride: Ride, val samples: List<RideSample>)
    private val pending = ArrayDeque<PendingRide>()
    private val writeMutex = Mutex()
    private val mutableSaveFailed = MutableStateFlow(false)
    val saveFailed = mutableSaveFailed.asStateFlow()

    fun enqueue(ride: Ride, samples: List<RideSample>) = synchronized(pending) {
        pending.addLast(PendingRide(ride, samples.toList()))
    }

    /** A failed insert leaves the same immutable ride at the front for an explicit retry. */
    suspend fun flush(): Boolean = writeMutex.withLock {
        while (true) {
            val next = synchronized(pending) { pending.firstOrNull() } ?: break
            val saved = withContext(NonCancellable) {
                // Once the transaction begins, cancellation cannot abandon its successful
                // result and leave an already-committed ride queued for a duplicate retry.
                val id = try {
                    insert(next.ride, next.samples)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    mutableSaveFailed.value = true
                    onFailure(error)
                    return@withContext false
                }
                synchronized(pending) { pending.removeFirst() }
                onSaved(id, next.ride)
                true
            }
            if (!saved) return@withLock false
        }
        mutableSaveFailed.value = false
        true
    }
}
