package com.spaceboy.ridebuddy.core.companion

import android.app.Activity
import android.bluetooth.le.ScanFilter
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.companion.ObservingDevicePresenceRequest
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.ParcelUuid
import androidx.core.content.edit
import com.spaceboy.ridebuddy.ble.BikeHogpServiceUuidString
import com.spaceboy.ridebuddy.ble.BikeIdentityRepository
import com.spaceboy.ridebuddy.ble.BikeNameFilter
import com.spaceboy.ridebuddy.ble.BluetoothAddress
import com.spaceboy.ridebuddy.ble.ProtectionAcceptanceStore
import com.spaceboy.ridebuddy.ble.isApriliaBikeName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The paired motorcycle. [associationId] is the system's handle for the association and is
 * what presence observation and removal are keyed on; it is nullable only for entries
 * restored from storage before the id could be read back.
 */
data class AssociatedBike(
    val bluetoothAddress: BluetoothAddress,
    val name: String,
    val associationId: Int? = null,
) {
    val address: String
        get() = bluetoothAddress.toString()
}

/**
 * Pairing state for the UI. [supported] is false on devices without companion-device
 * setup, where none of this is available at all.
 */
data class BikeAssociationState(
    val supported: Boolean,
    val bike: AssociatedBike? = null,
    val observingPresence: Boolean = false,
    val associationInProgress: Boolean = false,
    val errorMessage: String? = null,
)

/**
 * Owns the system companion-device association: pairing, presence observation, and removal.
 *
 * Deliberately not a connection manager. Associating tells Android to watch for the
 * motorcycle and wake the app when it appears; the GATT link itself belongs to
 * [com.spaceboy.ridebuddy.ble.AndroidBikeConnection], driven through the connection service.
 *
 * Association also anchors per-bike state. Storing a different motorcycle clears the
 * previous one's protection acceptance and identity, so nothing recorded against one bike
 * can be read as belonging to another.
 */
class BikeCompanionManager internal constructor(
    context: Context,
    private val protectionAcceptanceStore: ProtectionAcceptanceStore,
    private val bikeIdentityRepository: BikeIdentityRepository,
    applicationScope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private val deviceStore = AssociatedBikeStore(appContext)
    private val supported = appContext.packageManager.hasSystemFeature(
        PackageManager.FEATURE_COMPANION_DEVICE_SETUP,
    )
    private val manager: CompanionDeviceManager? = if (supported) {
        appContext.getSystemService(CompanionDeviceManager::class.java)
    } else null
    private val initialBike = deviceStore.read()
    private val mutableState = MutableStateFlow(BikeAssociationState(supported = supported, bike = initialBike))

    val state: StateFlow<BikeAssociationState> = mutableState.asStateFlow()

    init {
        initialBike?.bluetoothAddress?.let(bikeIdentityRepository::select)
        // Defer CDM Binder IPCs off the constructor so AppContainer creation
        // (and therefore Application.onCreate) doesn't block on a slow CDM call.
        // The synchronous deviceStore.read() above already populates the initial
        // state, so callers reading state.value get useful data immediately.
        applicationScope.launch(Dispatchers.IO) {
            refresh()
            ensurePresenceObservation()
        }
    }

    /**
     * Starts the system pairing picker.
     *
     * The picker is a system UI the rider chooses from; [launchApproval] hands its intent
     * back to an Activity to show. Success arrives either through [onAssociated] or, on
     * some platform versions, through [acceptActivityResult] — both paths converge on
     * [accept].
     */
    fun associate(
        launchApproval: (IntentSender) -> Unit,
        onAssociated: (AssociatedBike) -> Unit,
        onFailure: (String) -> Unit,
    ) {
        val companionManager = manager
        if (companionManager == null) {
            onFailure("Companion device setup is unavailable on this phone")
            return
        }
        mutableState.update { it.copy(associationInProgress = true, errorMessage = null) }

        // The CDM picker is scoped by both the bike's name family and the
        // SIG-standard HID-over-GATT service UUID. The combination is what
        // disambiguates the bike's LE interface (HOGP, advertises 0x1812) from
        // its BR/EDR audio endpoint (`…C8:5C`), which Android reports as DUAL
        // under the same advertised name. See BikeHogpServiceUuidString for the
        // captured scan record both halves of this filter were verified against.
        //
        // ParcelUuid is constructed here rather than as a top-level constant
        // because Robolectric's stub of android.os.ParcelUuid is partial and
        // can't be safely initialized from a JVM unit-test classloader.
        // fromString throws on malformed input rather than returning null, and
        // the argument is a compile-time constant, so there is nothing to guard.
        val hogpServiceUuid: ParcelUuid = ParcelUuid.fromString(BikeHogpServiceUuidString)
        val scanFilter = ScanFilter.Builder()
            .setServiceUuid(hogpServiceUuid)
            .build()
        val request = AssociationRequest.Builder()
            .addDeviceFilter(
                BluetoothLeDeviceFilter.Builder()
                    .setNamePattern(BikeNameFilter)
                    .setScanFilter(scanFilter)
                    .build(),
            )
            .setSingleDevice(false)
            .build()

        val callback = object : CompanionDeviceManager.Callback() {
            override fun onAssociationPending(intentSender: IntentSender) = launchApproval(intentSender)

            override fun onAssociationCreated(associationInfo: AssociationInfo) {
                accept(associationInfo)?.let(onAssociated)
            }

            override fun onFailure(error: CharSequence?) {
                fail("Pairing canceled", onFailure)
            }
        }
        runCatching {
            companionManager.associate(request, appContext.mainExecutor, callback)
        }.onFailure { fail(it.message ?: "Could not start bike association", onFailure) }
    }

    /** The Activity-result path out of the picker, for platforms that answer that way. */
    fun acceptActivityResult(resultCode: Int, data: Intent?): AssociatedBike? {
        if (resultCode != Activity.RESULT_OK || data == null) {
            mutableState.update { it.copy(associationInProgress = false) }
            return null
        }
        return data.getParcelableExtra(CompanionDeviceManager.EXTRA_ASSOCIATION, AssociationInfo::class.java)
            ?.let(::accept)
            .also {
                if (it == null) mutableState.update { state ->
                    state.copy(
                        associationInProgress = false,
                        errorMessage = "The selected device could not be read",
                    )
                }
            }
    }

    /**
     * Reconciles the local record with the system's associations.
     *
     * The rider can remove an association from system settings without the app being told,
     * so the system is authoritative: an association that has gone away clears the local
     * record along with that bike's protection acceptance and identity.
     *
     * A refresh that could not reach the service is a different matter — it says nothing
     * about the pairing, so the existing record is kept and only an error is surfaced.
     */
    fun refresh() {
        val companionManager = manager ?: return
        val stored = deviceStore.read()
        val refreshResult = runCatching {
            val associations = companionManager.myAssociations
            val selected = associations.firstOrNull { association ->
                association.bluetoothAddress() == stored?.bluetoothAddress
            } ?: associations.singleOrNull()
            selected?.let { association ->
                val address = association.bluetoothAddress() ?: return@let null
                // The picker writes a non-blank displayName for every successful
                // pick, so the only way we end up with a blank `name` here is a
                // CDM regression or a stored entry from an older schema. Surface
                // that as a blank UI label rather than silently substituting a
                // placeholder — `isApriliaBikeName` would otherwise reject any
                // such entry anyway.
                AssociatedBike(
                    bluetoothAddress = address,
                    name = association.displayName?.toString()?.takeIf(String::isNotBlank)
                        ?: stored?.name
                        ?: "",
                    associationId = association.id,
                )
            }
        }
        if (refreshResult.isFailure) {
            mutableState.update { state ->
                state.copy(
                    // A refresh that could not reach the CDM says nothing about the pairing.
                    bike = state.bike ?: stored,
                    associationInProgress = false,
                    errorMessage = refreshResult.exceptionOrNull()?.message?.takeIf(String::isNotBlank)
                        ?: "Could not refresh the motorcycle association",
                )
            }
            return
        }
        val refreshed = refreshResult.getOrNull()
        if (refreshed != null) {
            storeAssociation(refreshed)
        } else {
            stored?.bluetoothAddress?.let { address ->
                protectionAcceptanceStore.clear(address)
                bikeIdentityRepository.clear(address)
            }
            deviceStore.clear()
        }
        mutableState.update { state ->
            state.copy(
                bike = refreshed,
                observingPresence = refreshed != null && state.observingPresence,
                associationInProgress = false,
                errorMessage = null,
            )
        }
    }

    /**
     * Asks the system to watch for the motorcycle and wake the app when it appears. This is
     * what makes reconnection work without the app running. Idempotent.
     */
    fun ensurePresenceObservation() {
        val companionManager = manager ?: return
        val bike = state.value.bike ?: return
        if (state.value.observingPresence) return
        val result = runCatching {
            val id = bike.associationId ?: return@runCatching false
            companionManager.startObservingDevicePresence(
                ObservingDevicePresenceRequest.Builder().setAssociationId(id).build(),
            )
            true
        }
        mutableState.update {
            it.copy(
                observingPresence = result.getOrDefault(false),
                errorMessage = result.exceptionOrNull()?.message,
            )
        }
    }

    /**
     * Removes the association, returning whether it was actually removed.
     *
     * The local record is only cleared once the system has confirmed the removal — see
     * [canClearLocalAssociation]. Clearing it optimistically would strand a live system
     * association the app no longer knows about, which the rider could then only remove
     * from system settings.
     */
    fun forget(): Boolean {
        val companionManager = manager
        val bike = state.value.bike ?: deviceStore.read()
        var disassociationSucceeded = companionManager != null
        if (companionManager != null && bike != null) {
            val observationStopped = runCatching {
                val id = bike.associationId ?: return@runCatching
                companionManager.stopObservingDevicePresence(
                    ObservingDevicePresenceRequest.Builder().setAssociationId(id).build(),
                )
                Unit
            }.isSuccess
            val disassociation = runCatching {
                val id = bike.associationId ?: return@runCatching Unit
                companionManager.disassociate(id)
                Unit
            }
            disassociationSucceeded = disassociation.isSuccess
            if (!disassociationSucceeded) {
                val error = disassociation.exceptionOrNull()?.message?.takeIf(String::isNotBlank)
                    ?: "Could not remove the motorcycle association"
                if (observationStopped) {
                    mutableState.update { state -> state.copy(observingPresence = false) }
                    ensurePresenceObservation()
                }
                preserveAssociationAfterForgetFailure(bike, error)
            }
        }
        if (!canClearLocalAssociation(
                hasStoredAssociation = bike != null,
                companionSupported = supported,
                managerAvailable = companionManager != null,
                disassociationSucceeded = disassociationSucceeded,
            )
        ) {
            if (companionManager == null && bike != null) {
                preserveAssociationAfterForgetFailure(
                    bike,
                    "Companion device service is unavailable; try again",
                )
            }
            return false
        }
        bike?.bluetoothAddress?.let { address ->
            protectionAcceptanceStore.clear(address)
            bikeIdentityRepository.clear(address)
        }
        deviceStore.clear()
        mutableState.value = BikeAssociationState(supported = supported)
        return true
    }

    private fun preserveAssociationAfterForgetFailure(bike: AssociatedBike, message: String) {
        mutableState.update { state ->
            state.copy(
                bike = bike,
                errorMessage = message,
            )
        }
    }

    fun associatedBike(associationId: Int? = null): AssociatedBike? =
        // Return the cached state; callers (BikeCompanionDeviceService,
        // MainActivity.onResume) invoke refresh() explicitly when they need fresh data.
        // This makes the getter safe to call from Main-thread callbacks without
        // blocking on a CDM Binder IPC.
        state.value.bike?.takeIf { associationId == null || it.associationId == associationId }

    /**
     * Validates and records a device returned by the picker.
     *
     * The name is re-checked here even though the picker filtered on it, because this is
     * the last point before the address is trusted for connections: a peripheral that is
     * not this motorcycle family would fail authentication or, worse, be decoded with the
     * wrong telemetry layout.
     */
    private fun accept(associationInfo: AssociationInfo): AssociatedBike? {
        val scanResult = associationInfo.associatedDevice?.bleDevice
        val address = associationInfo.bluetoothAddress() ?: return null
        // No sentinel fallback: if both the scan-record and the CDM displayName
        // are blank, the device has no readable name and we must reject it. The
        // picker filters by name regex + 0x1812 UUID, so reaching `accept()` with
        // a blank name means the CDM is mis-reporting and we should not trust it.
        val name = scanResult?.scanRecord?.deviceName
            ?: associationInfo.displayName?.toString()?.takeIf(String::isNotBlank)
            ?: ""
        if (!name.isApriliaBikeName()) {
            rejectBikeName(name)
            return null
        }
        val bike = AssociatedBike(
            bluetoothAddress = address,
            name = name,
            associationId = associationInfo.id,
        )
        storeAssociation(bike)
        mutableState.update {
            it.copy(
                bike = bike,
                associationInProgress = false,
                errorMessage = null,
            )
        }
        ensurePresenceObservation()
        return bike
    }

    /**
     * The address, from whichever field carries it. The MAC bytes are preferred; the scan
     * result's text address is a fallback for platform versions that leave the bytes unset.
     */
    private fun AssociationInfo.bluetoothAddress(): BluetoothAddress? {
        BluetoothAddress.fromBytes(deviceMacAddress?.toByteArray())?.let { return it }
        return associatedDevice?.bleDevice?.device?.address?.let(BluetoothAddress::parse)
    }

    private fun fail(message: String, onFailure: (String) -> Unit) {
        mutableState.update { it.copy(associationInProgress = false, errorMessage = message) }
        onFailure(message)
    }

    private fun rejectBikeName(name: String) {
        val message = "The selected device '$name' is not a supported Aprilia RS 457 / Tuono 457"
        mutableState.update {
            it.copy(associationInProgress = false, errorMessage = message)
        }
    }

    /**
     * Persists the association, clearing the previous bike's per-bike state first when the
     * address has changed.
     */
    private fun storeAssociation(bike: AssociatedBike) {
        protectionAcceptanceToClear(deviceStore.read(), bike)
            ?.let { previousAddress ->
                protectionAcceptanceStore.clear(previousAddress)
                bikeIdentityRepository.clear(previousAddress)
            }
        deviceStore.write(bike)
        bikeIdentityRepository.select(bike.bluetoothAddress)
    }
}

/**
 * The address whose per-bike state must be discarded, or null when nothing changed.
 * Re-storing the same motorcycle must not clear its acceptance and force a fresh handshake.
 */
internal fun protectionAcceptanceToClear(
    previous: AssociatedBike?,
    replacement: AssociatedBike?,
): BluetoothAddress? = previous?.bluetoothAddress?.takeIf { it != replacement?.bluetoothAddress }

/**
 * Whether the local record may be dropped.
 *
 * True when there is nothing stored, when the platform has no companion support at all so
 * no system association can exist, or when the system confirmed the removal. Anything else
 * would leave a system association the app has forgotten about.
 */
internal fun canClearLocalAssociation(
    hasStoredAssociation: Boolean,
    companionSupported: Boolean,
    managerAvailable: Boolean,
    disassociationSucceeded: Boolean,
): Boolean = !hasStoredAssociation ||
        (!companionSupported && !managerAvailable) ||
        (managerAvailable && disassociationSucceeded)

/**
 * Local cache of the association, so the app knows which motorcycle it is paired with
 * without a system call on every launch. The system remains authoritative; see [refresh].
 */
class AssociatedBikeStore(context: Context, preferencesName: String = Name) {
    private val preferences = context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    fun read(): AssociatedBike? {
        if (!preferences.contains(KeyAddressValue)) return null
        val packedAddress = runCatching { preferences.getLong(KeyAddressValue, InvalidAddressValue) }.getOrNull()
            ?: return null
        val address = BluetoothAddress.fromLong(packedAddress) ?: return null
        val id = preferences.getInt(KeyAssociationId, MissingAssociationId).takeUnless { it == MissingAssociationId }
        // Legacy entries written before the 0x1812 picker tightening may have no
        // `KeyName` set. The address is still authoritative; surface a blank label
        // so the UI knows the entry needs a refresh instead of trusting a sentinel
        // placeholder.
        return AssociatedBike(
            bluetoothAddress = address,
            name = preferences.getString(KeyName, null).orEmpty(),
            associationId = id,
        )
    }

    fun write(bike: AssociatedBike) {
        preferences.edit {
            putLong(KeyAddressValue, bike.bluetoothAddress.toLong())
            putString(KeyName, bike.name)
            putInt(KeyAssociationId, bike.associationId ?: MissingAssociationId)
        }
    }

    fun clear() = preferences.edit { clear() }

    private companion object {
        const val Name = "associated_bike"
        const val KeyAddressValue = "address_value"
        const val KeyName = "name"
        const val KeyAssociationId = "association_id"
        const val MissingAssociationId = -1
        const val InvalidAddressValue = -1L
    }
}
