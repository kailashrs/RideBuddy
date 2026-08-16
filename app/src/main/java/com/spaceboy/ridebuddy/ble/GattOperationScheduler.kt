package com.spaceboy.ridebuddy.ble

import java.util.ArrayDeque

internal data class GattQueueUpdate(
    val removed: List<GattOperation>,
    val shouldStart: Boolean,
)

/**
 * Serializes Android GATT operations while keeping protocol-critical work in a dedicated lane.
 * The active operation is never preempted, but critical work always runs before queued normal work.
 */
internal class GattOperationScheduler {
    private val lock = Any()
    private val criticalQueue = ArrayDeque<GattOperation>()
    private val normalQueue = ArrayDeque<GattOperation>()
    private var activeOperation: GattOperation? = null

    fun enqueue(operation: GattOperation, front: Boolean = false): Boolean = synchronized(lock) {
        add(operation, front)
        activeOperation == null
    }

    fun enqueueAll(operations: List<GattOperation>, front: Boolean = false): Boolean = synchronized(lock) {
        addAll(operations, front)
        activeOperation == null
    }

    fun replaceQueued(
        removeIf: (GattOperation) -> Boolean,
        replacements: List<GattOperation>,
        front: Boolean,
    ): GattQueueUpdate = synchronized(lock) {
        val removed = removeMatching(criticalQueue, removeIf) + removeMatching(normalQueue, removeIf)
        addAll(replacements, front)
        GattQueueUpdate(removed, activeOperation == null)
    }

    fun beginNext(): GattOperation? = synchronized(lock) {
        if (activeOperation != null) return@synchronized null
        val next = criticalQueue.pollFirst() ?: normalQueue.pollFirst() ?: return@synchronized null
        activeOperation = next
        next
    }

    fun active(): GattOperation? = synchronized(lock) { activeOperation }

    fun activeMatching(predicate: (GattOperation) -> Boolean): GattOperation? = synchronized(lock) {
        activeOperation?.takeIf(predicate)
    }

    fun isActive(operation: GattOperation): Boolean = synchronized(lock) {
        activeOperation === operation
    }

    fun complete(operation: GattOperation): Boolean = synchronized(lock) {
        if (activeOperation !== operation) return@synchronized false
        activeOperation = null
        true
    }

    fun retry(operation: GattOperation): Boolean = synchronized(lock) {
        if (activeOperation !== operation) return@synchronized false
        activeOperation = null
        add(operation.retry(), front = true)
        true
    }

    fun removeQueued(predicate: (GattOperation) -> Boolean): List<GattOperation> = synchronized(lock) {
        removeMatching(criticalQueue, predicate) + removeMatching(normalQueue, predicate)
    }

    fun hasNoActiveOperation(): Boolean = synchronized(lock) { activeOperation == null }

    fun clear(): List<GattOperation> = synchronized(lock) {
        buildList {
            activeOperation?.let(::add)
            addAll(criticalQueue)
            addAll(normalQueue)
        }.also {
            activeOperation = null
            criticalQueue.clear()
            normalQueue.clear()
        }
    }

    private fun addAll(operations: List<GattOperation>, front: Boolean) {
        if (front) {
            operations.asReversed().forEach { add(it, front = true) }
        } else {
            operations.forEach { add(it, front = false) }
        }
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
