package com.spaceboy.ridebuddy.core.companion

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanResult
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.companion.ObservingDevicePresenceRequest
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import com.spaceboy.ridebuddy.ble.DiscoveredBike
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class AssociatedBike(
    val address: String,
    val name: String,
    val associationId: Int? = null,
)

data class BikeAssociationState(
    val supported: Boolean,
    val bike: AssociatedBike? = null,
    val observingPresence: Boolean = false,
    val associationInProgress: Boolean = false,
    val errorMessage: String? = null,
)

/** Owns the system association only. BLE GATT remains the responsibility of BikeConnectionService. */
class BikeCompanionManager(context: Context) {
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
        onAssociated: (DiscoveredBike) -> Unit,
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
                    .setNamePattern(com.spaceboy.ridebuddy.ble.ApriliaBikeNamePattern)
                    .build(),
            )
            .setSingleDevice(false)
            .build()
        val callback = object : CompanionDeviceManager.Callback() {
            @Deprecated("Used by Android 8-12L")
            override fun onDeviceFound(intentSender: IntentSender) = launchApproval(intentSender)

            override fun onAssociationPending(intentSender: IntentSender) = launchApproval(intentSender)

            @RequiresApi(Build.VERSION_CODES.TIRAMISU)
            override fun onAssociationCreated(associationInfo: AssociationInfo) {
                accept(associationInfo)?.let(onAssociated)
            }

            override fun onFailure(error: CharSequence?) {
                fail("Pairing canceled", onFailure)
            }
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                companionManager.associate(request, appContext.mainExecutor, callback)
            } else {
                @Suppress("DEPRECATION")
                companionManager.associate(request, callback, Handler(Looper.getMainLooper()))
            }
        }.onFailure { fail(it.message ?: "Could not start bike association", onFailure) }
    }

    fun acceptActivityResult(resultCode: Int, data: Intent?): DiscoveredBike? {
        if (resultCode != Activity.RESULT_OK || data == null) {
            mutableState.update { it.copy(associationInProgress = false) }
            return null
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            data.getParcelableExtra(CompanionDeviceManager.EXTRA_ASSOCIATION, AssociationInfo::class.java)
                ?.let(::accept)
        } else {
            @Suppress("DEPRECATION")
            when (val device = data.getParcelableExtra<android.os.Parcelable>(CompanionDeviceManager.EXTRA_DEVICE)) {
                is ScanResult -> accept(device.device, device.scanRecord?.deviceName, device.rssi)
                is BluetoothDevice -> accept(device, null, 0)
                else -> null
            }
        }.also {
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
        val refreshed = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val associations = companionManager.myAssociations
                val selected = associations.firstOrNull { association ->
                    association.deviceMacAddress?.toString().equals(stored?.address, ignoreCase = true)
                } ?: associations.singleOrNull()
                selected?.let { association ->
                    val address = association.deviceMacAddress?.toString() ?: return@let null
                    AssociatedBike(
                        address = address,
                        name = association.displayName?.toString()?.takeIf(String::isNotBlank)
                            ?: stored?.name
                            ?: DefaultBikeName,
                        associationId = association.id,
                    )
                }
            } else {
                @Suppress("DEPRECATION")
                val address = companionManager.associations.firstOrNull { it.equals(stored?.address, ignoreCase = true) }
                    ?: companionManager.associations.singleOrNull()
                address?.let { AssociatedBike(it, stored?.name ?: DefaultBikeName) }
            }
        }.getOrNull()
        if (refreshed != null) deviceStore.write(refreshed) else deviceStore.clear()
        mutableState.update { state ->
            state.copy(
                bike = refreshed,
                observingPresence = refreshed != null && state.observingPresence,
                associationInProgress = false,
            )
        }
    }

    fun ensurePresenceObservation() {
        val companionManager = manager ?: return
        val bike = state.value.bike ?: return
        if (state.value.observingPresence) return
        val result = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
                val id = bike.associationId ?: return@runCatching false
                companionManager.startObservingDevicePresence(
                    ObservingDevicePresenceRequest.Builder().setAssociationId(id).build(),
                )
            } else {
                @Suppress("DEPRECATION")
                companionManager.startObservingDevicePresence(bike.address)
            }
            true
        }
        mutableState.update {
            it.copy(
                observingPresence = result.getOrDefault(false),
                errorMessage = result.exceptionOrNull()?.message,
            )
        }
    }

    fun forget() {
        val companionManager = manager
        val bike = state.value.bike
        if (companionManager != null && bike != null) {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA && bike.associationId != null) {
                    companionManager.stopObservingDevicePresence(
                        ObservingDevicePresenceRequest.Builder().setAssociationId(bike.associationId).build(),
                    )
                } else {
                    @Suppress("DEPRECATION")
                    companionManager.stopObservingDevicePresence(bike.address)
                }
            }
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && bike.associationId != null) {
                    companionManager.disassociate(bike.associationId)
                } else {
                    @Suppress("DEPRECATION")
                    companionManager.disassociate(bike.address)
                }
            }
        }
        deviceStore.clear()
        mutableState.value = BikeAssociationState(supported = supported)
    }

    fun associatedBike(associationId: Int? = null): AssociatedBike? {
        refresh()
        return state.value.bike?.takeIf { associationId == null || it.associationId == associationId }
    }

    fun rememberLegacyBike(bike: DiscoveredBike) {
        val associated = AssociatedBike(bike.address, bike.name)
        deviceStore.write(associated)
        mutableState.update { it.copy(bike = associated) }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun accept(associationInfo: AssociationInfo): DiscoveredBike? {
        val address = associationInfo.deviceMacAddress?.toString() ?: return null
        val scanResult = if (Build.VERSION.SDK_INT >= 34) associationInfo.associatedDevice?.bleDevice else null
        val name = scanResult?.scanRecord?.deviceName
            ?: associationInfo.displayName?.toString()?.takeIf(String::isNotBlank)
            ?: DefaultBikeName
        val bike = AssociatedBike(address, name, associationInfo.id)
        deviceStore.write(bike)
        mutableState.update {
            it.copy(
                bike = bike,
                associationInProgress = false,
                errorMessage = null,
            )
        }
        ensurePresenceObservation()
        return DiscoveredBike(name, address, scanResult?.rssi ?: 0)
    }

    @SuppressLint("MissingPermission")
    private fun accept(device: BluetoothDevice, advertisedName: String?, rssi: Int): DiscoveredBike {
        val name = advertisedName?.takeIf(String::isNotBlank)
            ?: runCatching { device.name }.getOrNull()?.takeIf(String::isNotBlank)
            ?: DefaultBikeName
        val bike = AssociatedBike(device.address, name)
        deviceStore.write(bike)
        mutableState.update {
            it.copy(
                bike = bike,
                associationInProgress = false,
                errorMessage = null,
            )
        }
        ensurePresenceObservation()
        return DiscoveredBike(name, device.address, rssi)
    }

    private fun fail(message: String, onFailure: (String) -> Unit) {
        mutableState.update { it.copy(associationInProgress = false, errorMessage = message) }
        onFailure(message)
    }

    private companion object {
        const val DefaultBikeName = "Motorcycle"
    }
}

class AssociatedBikeStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(Name, Context.MODE_PRIVATE)

    fun read(): AssociatedBike? {
        val address = preferences.getString(KeyAddress, null)?.takeIf(String::isNotBlank) ?: return null
        val id = preferences.getInt(KeyAssociationId, MissingAssociationId).takeUnless { it == MissingAssociationId }
        return AssociatedBike(address, preferences.getString(KeyName, null) ?: "Motorcycle", id)
    }

    fun write(bike: AssociatedBike) {
        preferences.edit {
            putString(KeyAddress, bike.address)
            putString(KeyName, bike.name)
            putInt(KeyAssociationId, bike.associationId ?: MissingAssociationId)
        }
    }

    fun clear() = preferences.edit { clear() }

    private companion object {
        const val Name = "associated_bike"
        const val KeyAddress = "address"
        const val KeyName = "name"
        const val KeyAssociationId = "association_id"
        const val MissingAssociationId = -1
    }
}
