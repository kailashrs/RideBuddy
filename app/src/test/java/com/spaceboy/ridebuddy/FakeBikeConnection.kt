package com.spaceboy.ridebuddy

import com.spaceboy.ridebuddy.domain.BikeConnection
import com.spaceboy.ridebuddy.domain.BikeConnectionState
import com.spaceboy.ridebuddy.domain.BikeConnectionTarget
import com.spaceboy.ridebuddy.domain.BikeControlEvent
import com.spaceboy.ridebuddy.domain.BikeIdentity
import com.spaceboy.ridebuddy.domain.BikeWrite
import com.spaceboy.ridebuddy.domain.BleDiagnostics
import com.spaceboy.ridebuddy.domain.TelemetryReading
import java.util.UUID
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/** A connected bike that records every write, for tests of what is sent to the cluster. */
internal open class FakeBikeConnection(
    state: BikeConnectionState = BikeConnectionState.Connected("RS 457", null),
) : BikeConnection {
    val writes = mutableListOf<BikeWrite>()
    override val connectionState = MutableStateFlow(state)
    override val readings = MutableSharedFlow<TelemetryReading>(extraBufferCapacity = 64)
    override val latestReading = MutableStateFlow<TelemetryReading?>(null)
    override val identity = MutableStateFlow(BikeIdentity())
    override val diagnostics = MutableStateFlow(BleDiagnostics(authenticated = true))
    override val controls = MutableSharedFlow<BikeControlEvent>(extraBufferCapacity = 8)

    override fun connect(target: BikeConnectionTarget) = Unit
    override fun disconnect() = Unit

    override fun enqueueWrite(characteristic: UUID, payload: ByteArray) {
        synchronized(writes) { writes += BikeWrite(characteristic, payload) }
    }

    override suspend fun writeAndAwait(write: BikeWrite): Boolean {
        synchronized(writes) { writes += write }
        return true
    }
}
