package com.spaceboy.ridebuddy.ble

import android.net.MacAddress
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.dataStoreFile
import com.spaceboy.ridebuddy.data.JsonSerializer
import com.spaceboy.ridebuddy.domain.BikeIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable

/**
 * What this phone remembers about its link to one motorcycle. Kept apart from settings because
 * it describes a bond on this device and must not be restored onto another.
 *
 * Addresses are stored as [MacAddress.toString] gives them: lower-case and colon-separated.
 */
@Serializable
internal data class LinkState(
    /** The bike that has accepted a protection response; see [ProtectionAcceptanceStore]. */
    val protectionAcceptedAddress: String? = null,
    val identity: StoredBikeIdentity? = null,
    /** See [com.spaceboy.ridebuddy.core.companion.BikeConnectionDemandController]. */
    val automaticConnectionSuppressed: Boolean = false,
    val awaitingBleAppearance: Boolean = false,
)

@Serializable
internal data class StoredBikeIdentity(
    val address: String,
    val vin: String? = null,
    val clusterSoftwareVersion: String? = null,
    val lastConnectedAtMillis: Long? = null,
) {
    fun toBikeIdentity() = BikeIdentity(vin, clusterSoftwareVersion, lastConnectedAtMillis)
}


/** Current value synchronously, writes serialised in call order. */
internal class LinkStateStore(
    private val store: DataStore<LinkState>,
    private val scope: CoroutineScope,
) {
    val state: StateFlow<LinkState> =
        store.data.stateIn(scope, SharingStarted.Eagerly, runBlocking { store.data.first() })

    private val writeDispatcher = Dispatchers.IO.limitedParallelism(1)

    fun update(transform: (LinkState) -> LinkState) {
        scope.launch(writeDispatcher) { store.updateData(transform) }
    }

    companion object {
        const val FileName = "link_state.json"

        fun create(context: Context, scope: CoroutineScope) = LinkStateStore(
            DataStoreFactory.create(
                serializer = JsonSerializer(LinkState.serializer(), LinkState()),
                scope = CoroutineScope(scope.coroutineContext + Dispatchers.IO),
                produceFile = { context.dataStoreFile(FileName) },
            ),
            scope,
        )
    }
}
