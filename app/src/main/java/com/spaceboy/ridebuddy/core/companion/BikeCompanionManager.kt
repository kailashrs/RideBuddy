package com.spaceboy.ridebuddy.core.companion

import android.app.Activity
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.companion.ObservingDevicePresenceRequest
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import androidx.core.content.edit
import com.spaceboy.ridebuddy.ble.BluetoothAddress
import com.spaceboy.ridebuddy.ble.ProtectionAcceptanceStore
import com.spaceboy.ridebuddy.ble.hasUnsupportedTelemetryLayout
import com.spaceboy.ridebuddy.ble.isApriliaBikeName
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class AssociatedBike(
    val bluetoothAddress: BluetoothAddress,
    val name: String,
    val associationId: Int? = null,
) {
    val address: String
        get() = bluetoothAddress.toString()
}

data class BikeAssociationState(
    val supported: Boolean,
    val bike: AssociatedBike? = null,
    val observingPresence: Boolean = false,
    val associationInProgress: Boolean = false,
    val errorMessage: String? = null,
)

/** Owns the system association only. BLE GATT remains the responsibility of BikeConnectionService. */
class BikeCompanionManager internal constructor(
    context: Context,
    private val protectionAcceptanceStore: ProtectionAcceptanceStore,
) {
    private val appContext = context.applicationContext
    private val deviceStore = AssociatedBikeStore(appContext)
    private val supported = appContext.packageManager.hasSystemFeature(
        PackageManager.FEATURE_COMPANION_DEVICE_SETUP,
    )
    private val manager: CompanionDeviceManager? = if (supported) {
        appContext.getSystemService(CompanionDeviceManager::class.java)
    } else null
    private val mutableState = MutableStateFlow(
        BikeAssociationState(supported = supported, bike = deviceStore.read()),
    )

    val state: StateFlow<BikeAssociationState> = mutableState.asStateFlow()

    init {
        refresh()
        ensurePresenceObservation()
    }

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
        val request = AssociationRequest.Builder()
            .addDeviceFilter(
                BluetoothLeDeviceFilter.Builder()
                    .setNamePattern(com.spaceboy.ridebuddy.ble.CdmAcceptAllBikeNames)
                    .build(),
            )
            .setSingleDevice(false)
            .build()
        val callback = object : CompanionDeviceManager.Callback() {
            @Deprecated("Used by Android 8-12L", ReplaceWith("launchApproval(intentSender)"))
            override fun onDeviceFound(intentSender: IntentSender) = launchApproval(intentSender)

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
                AssociatedBike(
                    bluetoothAddress = address,
                    name = association.displayName?.toString()?.takeIf(String::isNotBlank)
                        ?: stored?.name
                        ?: DefaultBikeName,
                    associationId = association.id,
                )
            }
        }
        if (refreshResult.isFailure) {
            mutableState.update { state ->
                state.copy(
                    bike = preservedAssociationAfterRefreshFailure(state.bike, stored),
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
            stored?.bluetoothAddress?.let(protectionAcceptanceStore::clear)
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
        bike?.bluetoothAddress?.let(protectionAcceptanceStore::clear)
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

    fun associatedBike(associationId: Int? = null): AssociatedBike? {
        refresh()
        return state.value.bike?.takeIf { associationId == null || it.associationId == associationId }
    }

    private fun accept(associationInfo: AssociationInfo): AssociatedBike? {
        val scanResult = associationInfo.associatedDevice?.bleDevice
        val address = associationInfo.bluetoothAddress() ?: return null
        val name = scanResult?.scanRecord?.deviceName
            ?: associationInfo.displayName?.toString()?.takeIf(String::isNotBlank)
            ?: DefaultBikeName
        if (!isAcceptableAssociationName(name)) {
            rejectAssociation(name)
            return null
        }
        val bike = AssociatedBike(address, name, associationInfo.id)
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

    private fun AssociationInfo.bluetoothAddress(): BluetoothAddress? {
        BluetoothAddress.fromBytes(deviceMacAddress?.toByteArray())?.let { return it }
        return associatedDevice?.bleDevice?.device?.address?.let(BluetoothAddress::parse)
    }

    private fun fail(message: String, onFailure: (String) -> Unit) {
        mutableState.update { it.copy(associationInProgress = false, errorMessage = message) }
        onFailure(message)
    }

    /**
     * After the user picks a device from the CDM picker, validate that it matches an
     * RS 457/Tuono 457 name family and is not in the SR family whose telemetry layout
     * is intentionally unsupported. MIA is intentionally not matched either way (the
     * OEM app does not recognise it).
     */
    private fun isAcceptableAssociationName(name: String): Boolean {
        if (name == DefaultBikeName) return true // System gave us only the generic display name.
        if (name.hasUnsupportedTelemetryLayout()) return false
        return name.isApriliaBikeName()
    }

    private fun rejectAssociation(name: String) {
        val message = "The selected device '$name' is not a supported Aprilia RS 457 / Tuono 457"
        mutableState.update {
            it.copy(associationInProgress = false, errorMessage = message)
        }
    }

    private fun storeAssociation(bike: AssociatedBike) {
        protectionAcceptanceToClear(deviceStore.read(), bike)
            ?.let(protectionAcceptanceStore::clear)
        deviceStore.write(bike)
    }

    private companion object {
        const val DefaultBikeName = "Motorcycle"
    }
}

internal fun preservedAssociationAfterRefreshFailure(
    current: AssociatedBike?,
    stored: AssociatedBike?,
): AssociatedBike? = current ?: stored

internal fun protectionAcceptanceToClear(
    previous: AssociatedBike?,
    replacement: AssociatedBike?,
): BluetoothAddress? = previous?.bluetoothAddress?.takeIf { it != replacement?.bluetoothAddress }

internal fun canClearLocalAssociation(
    hasStoredAssociation: Boolean,
    companionSupported: Boolean,
    managerAvailable: Boolean,
    disassociationSucceeded: Boolean,
): Boolean = !hasStoredAssociation ||
        (!companionSupported && !managerAvailable) ||
        (managerAvailable && disassociationSucceeded)

class AssociatedBikeStore(context: Context, preferencesName: String = Name) {
    private val preferences = context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    fun read(): AssociatedBike? {
        if (!preferences.contains(KeyAddressValue)) return null
        val packedAddress = runCatching { preferences.getLong(KeyAddressValue, InvalidAddressValue) }.getOrNull()
            ?: return null
        val address = BluetoothAddress.fromLong(packedAddress) ?: return null
        val id = preferences.getInt(KeyAssociationId, MissingAssociationId).takeUnless { it == MissingAssociationId }
        return AssociatedBike(address, preferences.getString(KeyName, null) ?: "Motorcycle", id)
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
