package com.spaceboy.ridebuddy.ble

import android.net.MacAddress
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.dataStoreFile
import androidx.datastore.migrations.SharedPreferencesMigration
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
                migrations = legacyLinkStateMigrations(context),
                scope = CoroutineScope(scope.coroutineContext + Dispatchers.IO),
                produceFile = { context.dataStoreFile(FileName) },
            ),
            scope,
        )
    }
}

/**
 * Reads the pre-1.1 preference files once. The protection flag matters most: the cluster will
 * not issue a second challenge after accepting one, so losing it would stall every connection
 * until the bike is paired again.
 */
internal fun legacyLinkStateMigrations(context: Context) = listOf(
    SharedPreferencesMigration<LinkState>(context, "ble_protection_trust") { old, state ->
        state.copy(protectionAcceptedAddress = legacyAddress(old.getLong("trusted_address", -1L)))
    },
    SharedPreferencesMigration<LinkState>(context, "bike_identity") { old, state ->
        val address = legacyAddress(old.getLong("address", -1L)) ?: return@SharedPreferencesMigration state
        state.copy(
            identity = StoredBikeIdentity(
                address = address,
                vin = old.getString("vin")?.takeIf(String::isNotBlank),
                clusterSoftwareVersion = old.getString("cluster_software")?.takeIf(String::isNotBlank),
                lastConnectedAtMillis = old.getLong("last_connected", -1L).takeIf { it >= 0 },
            ),
        )
    },
    SharedPreferencesMigration<LinkState>(context, "bike_connection_demand") { old, state ->
        state.copy(
            automaticConnectionSuppressed = old.getBoolean("automatic_connection_suppressed", false),
            awaitingBleAppearance = old.getBoolean("awaiting_ble_appearance", false),
        )
    },
)

/** The previous builds packed the address into a `Long`. */
internal fun legacyAddress(packed: Long): String? = packed.takeIf { it in 0..0xFFFFFFFFFFFFL }?.let {
    MacAddress.fromBytes(ByteArray(6) { index -> (packed ushr ((5 - index) * 8)).toByte() }).toString()
}
