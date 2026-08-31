package com.spaceboy.ridebuddy.ble

import java.util.ArrayDeque

/**
 * Serializes Android GATT operations while keeping protocol-critical work in a dedicated lane.
 * The active operation is never preempted, but critical work always runs before queued normal work.
 *
 * Confined to the connection's main handler, which is the only thread that enqueues, begins,
 * completes or clears work. The reentrancy hazard [GattOperationCoordinator] guards against —
 * Android entering a callback synchronously from a `BluetoothGatt` call — happens on that same
 * thread, so a monitor would not have helped with it either.
 */
internal class GattOperationScheduler {
    private val criticalQueue = ArrayDeque<GattOperation>()
    private val normalQueue = ArrayDeque<GattOperation>()
    private var activeOperation: GattOperation? = null

    /** Returns true when nothing is in flight, so the caller should start the queue running. */
    fun enqueue(operation: GattOperation): Boolean {
        add(operation, front = false)
        return activeOperation == null
    }

    /** Bulk [enqueue], used to queue a whole subscription set in one deterministic order. */
    fun enqueueAll(operations: List<GattOperation>): Boolean {
        operations.forEach { add(it, front = false) }
        return activeOperation == null
    }

    /**
     * Promotes the next operation to active and returns it, or null when something is
     * already in flight or both lanes are empty. Critical work is drained first, so a
     * protocol step never queues behind a batch of routine display writes.
     */
    fun beginNext(): GattOperation? {
        if (activeOperation != null) return null
        val next = criticalQueue.pollFirst() ?: normalQueue.pollFirst() ?: return null
        activeOperation = next
        return next
    }

    fun active(): GattOperation? = activeOperation

    fun activeMatching(predicate: (GattOperation) -> Boolean): GattOperation? =
        activeOperation?.takeIf(predicate)

    fun isActive(operation: GattOperation): Boolean = activeOperation === operation

    /**
     * Clears [operation] as the active one. Identity-compared, and false when it is not
     * active, so a duplicate or late completion callback cannot free the slot belonging
     * to whatever started after it.
     */
    fun complete(operation: GattOperation): Boolean {
        if (activeOperation !== operation) return false
        activeOperation = null
        return true
    }

    /** Puts a retry at the head of its own lane so it keeps its place in the protocol sequence. */
    fun retry(operation: GattOperation): Boolean {
        if (activeOperation !== operation) return false
        activeOperation = null
        add(operation.retry(), front = true)
        return true
    }

    /**
     * Drops queued operations matching [predicate] and returns them, so the caller can
     * fail them explicitly. The active operation is left alone — it is already with the
     * platform and will complete or time out on its own.
     */
    fun removeQueued(predicate: (GattOperation) -> Boolean): List<GattOperation> =
        removeMatching(criticalQueue, predicate) + removeMatching(normalQueue, predicate)

    /** Empties both lanes and the active slot, returning everything abandoned. */
    fun clear(): List<GattOperation> = buildList {
        activeOperation?.let(::add)
        addAll(criticalQueue)
        addAll(normalQueue)
    }.also {
        activeOperation = null
        criticalQueue.clear()
        normalQueue.clear()
    }

    private fun add(operation: GattOperation, front: Boolean) {
        val queue = when (operation.priority) {
            GattOperationPriority.Critical -> criticalQueue
            GattOperationPriority.Normal -> normalQueue
        }
        if (front) queue.addFirst(operation) else queue.addLast(operation)
    }

    private fun removeMatching(
        queue: ArrayDeque<GattOperation>,
        predicate: (GattOperation) -> Boolean,
    ): List<GattOperation> = buildList {
        val iterator = queue.iterator()
        while (iterator.hasNext()) {
            val operation = iterator.next()
            if (predicate(operation)) {
                iterator.remove()
                add(operation)
            }
        }
    }
}
