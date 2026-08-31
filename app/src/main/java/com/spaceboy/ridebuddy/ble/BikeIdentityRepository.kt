package com.spaceboy.ridebuddy.ble

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.spaceboy.ridebuddy.domain.BikeIdentity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Persistence for the identity values read off a motorcycle. Address-scoped: reading with
 * an address other than the stored one yields an empty identity rather than another
 * bike's VIN.
 */
internal interface BikeIdentityStore {
    fun read(address: BluetoothAddress): BikeIdentity
    fun write(address: BluetoothAddress, identity: BikeIdentity)
    fun clear(address: BluetoothAddress)
}

/** Stores the most recently associated motorcycle identity without performing disk I/O on Main. */
internal class SharedPreferencesBikeIdentityStore(
    context: Context,
    preferencesName: String = PreferencesName,
) : BikeIdentityStore {
    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    override fun read(address: BluetoothAddress): BikeIdentity {
        val storedAddress = runCatching {
            preferences.getLong(KeyAddress, InvalidAddress)
        }.getOrDefault(InvalidAddress)
        if (storedAddress != address.toLong()) return BikeIdentity()

        return BikeIdentity(
            vin = preferences.getString(KeyVin, null)?.takeIf(String::isNotBlank),
            clusterSoftwareVersion = preferences.getString(KeyClusterSoftware, null)
                ?.takeIf(String::isNotBlank),
            lastConnectedAtMillis = preferences.getLong(KeyLastConnected, MissingTimestamp)
                .takeUnless { it == MissingTimestamp },
        )
    }

    @SuppressLint("ApplySharedPref")
    override fun write(address: BluetoothAddress, identity: BikeIdentity) {
        // Identity values arrive from separate reads that can land in either order and
        // either of which may fail, so a write carries only what is known so far. Merging
        // against what is stored stops a partial update from erasing a field read earlier.
        val merged = mergeBikeIdentity(identity, read(address))
        preferences.edit(commit = true) {
            putLong(KeyAddress, address.toLong())
            putNullableString(KeyVin, merged.vin)
            putNullableString(KeyClusterSoftware, merged.clusterSoftwareVersion)
            putNullableLong(KeyLastConnected, merged.lastConnectedAtMillis)
        }
    }

    @SuppressLint("ApplySharedPref")
    override fun clear(address: BluetoothAddress) {
        if (preferences.getLong(KeyAddress, InvalidAddress) == address.toLong()) {
            preferences.edit(commit = true) { clear() }
        }
    }

    private fun SharedPreferences.Editor.putNullableString(
        key: String,
        value: String?,
    ): SharedPreferences.Editor = if (value == null) remove(key) else putString(key, value)

    private fun SharedPreferences.Editor.putNullableLong(
        key: String,
        value: Long?,
    ): SharedPreferences.Editor = if (value == null) remove(key) else putLong(key, value)

    private companion object {
        const val PreferencesName = "bike_identity"
        const val KeyAddress = "address"
        const val KeyVin = "vin"
        const val KeyClusterSoftware = "cluster_software"
        const val KeyLastConnected = "last_connected"
        const val InvalidAddress = -1L
        const val MissingTimestamp = -1L
    }
}

/**
 * Owns the identity shown by the UI for the currently selected motorcycle.
 *
 * Two concerns meet here. Persistence is serialised onto one IO consumer so callbacks
 * never block on storage and writes cannot reorder. Selection is generation-counted so a
 * slow read for a bike the rider has already switched away from is discarded on arrival
 * rather than overwriting the new bike's identity.
 *
 * The lock covers the selection state and the published value together, which is what
 * makes "read the selection, then publish" atomic with respect to a concurrent [clear].
 */
internal class BikeIdentityRepository(
    private val store: BikeIdentityStore,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private sealed interface PersistenceCommand {
        data class Write(val address: BluetoothAddress, val identity: BikeIdentity) : PersistenceCommand
        data class Clear(val address: BluetoothAddress) : PersistenceCommand
    }

    private val lock = Any()
    private val mutableIdentity = MutableStateFlow(BikeIdentity())
    private val persistenceCommands = Channel<PersistenceCommand>(Channel.UNLIMITED)
    private var selectedAddress: BluetoothAddress? = null
    private var selectionGeneration = 0L

    val identity: StateFlow<BikeIdentity> = mutableIdentity.asStateFlow()

    init {
        scope.launch(ioDispatcher) {
            for (command in persistenceCommands) {
                when (command) {
                    is PersistenceCommand.Write -> store.write(command.address, command.identity)
                    is PersistenceCommand.Clear -> store.clear(command.address)
                }
            }
        }
    }

    /**
     * Points the repository at a motorcycle and loads its stored identity in the
     * background. Re-selecting the bike already in play is a no-op, so a reconnect does
     * not blank the displayed values.
     */
    fun select(address: BluetoothAddress) {
        val generation = synchronized(lock) {
            if (selectedAddress == address) {
                null
            } else {
                selectedAddress = address
                selectionGeneration += 1
                mutableIdentity.value = BikeIdentity()
                selectionGeneration
            }
        } ?: return

        scope.launch(ioDispatcher) {
            val stored = store.read(address)
            synchronized(lock) {
                if (selectedAddress == address && selectionGeneration == generation) {
                    mutableIdentity.value = mergeBikeIdentity(mutableIdentity.value, stored)
                }
            }
        }
    }

    /**
     * Applies a live value read from [address]. Silently ignored when that is not the
     * selected bike — a late callback from a superseded connection.
     */
    fun update(address: BluetoothAddress, transform: (BikeIdentity) -> BikeIdentity) {
        synchronized(lock) {
            if (selectedAddress != address) return
            val snapshot = transform(mutableIdentity.value)
            mutableIdentity.value = snapshot
            persistenceCommands.trySend(PersistenceCommand.Write(address, snapshot))
        }
    }

    fun clear(address: BluetoothAddress) {
        synchronized(lock) {
            if (selectedAddress == address) {
                selectedAddress = null
                selectionGeneration += 1
                mutableIdentity.value = BikeIdentity()
            }
            // Queue the clear while holding the same lock as update(). This guarantees that a
            // callback already in flight is persisted before the clear, never after it.
            persistenceCommands.trySend(PersistenceCommand.Clear(address))
        }
    }
}

/**
 * Combines a live identity with a stored one. Live values win field by field, so a
 * freshly read VIN replaces a remembered one while fields not read this session survive.
 * The connection timestamp is the exception: the most recent of the two is always right.
 */
internal fun mergeBikeIdentity(current: BikeIdentity, stored: BikeIdentity): BikeIdentity = BikeIdentity(
    vin = current.vin ?: stored.vin,
    clusterSoftwareVersion = current.clusterSoftwareVersion ?: stored.clusterSoftwareVersion,
    lastConnectedAtMillis = listOfNotNull(current.lastConnectedAtMillis, stored.lastConnectedAtMillis).maxOrNull(),
)
