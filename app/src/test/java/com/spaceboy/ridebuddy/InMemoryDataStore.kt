package com.spaceboy.ridebuddy

import androidx.datastore.core.DataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A DataStore without a file, for tests of the code built on one. */
internal class InMemoryDataStore<T>(initial: T) : DataStore<T> {
    private val state = MutableStateFlow(initial)
    private val mutex = Mutex()
    override val data: Flow<T> = state

    val value: T get() = state.value

    override suspend fun updateData(transform: suspend (t: T) -> T): T =
        mutex.withLock { transform(state.value).also { state.value = it } }
}
