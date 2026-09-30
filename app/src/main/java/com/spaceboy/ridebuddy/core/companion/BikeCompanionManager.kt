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
import android.net.MacAddress
import android.os.ParcelUuid
import android.util.Log
import com.spaceboy.ridebuddy.ble.BikeHogpServiceUuidString
import com.spaceboy.ridebuddy.ble.BikeIdentityRepository
import com.spaceboy.ridebuddy.ble.BikeNameFilter
import com.spaceboy.ridebuddy.ble.ProtectionAcceptanceStore
import com.spaceboy.ridebuddy.ble.isApriliaBikeName
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** The paired motorcycle, as the system's companion association describes it. */
data class AssociatedBike(
    val address: MacAddress,
    val name: String,
    val associationId: Int,
)

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
 * The system's association is the only record of which bike is paired; nothing is cached
 * locally. The GATT link itself belongs to [com.spaceboy.ridebuddy.ble.AndroidBikeConnection].
 * Per-bike state — protection acceptance and identity — is dropped whenever the associated
 * bike goes away or changes, so nothing recorded against one bike is read as another's.
 */
class BikeCompanionManager internal constructor(
    context: Context,
    private val protectionAcceptanceStore: ProtectionAcceptanceStore,
    private val bikeIdentityRepository: BikeIdentityRepository,
) {
    private val appContext = context.applicationContext
    private val manager: CompanionDeviceManager? =
        if (appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP)) {
            appContext.getSystemService(CompanionDeviceManager::class.java)
        } else {
            null
        }
    private val mutableState = MutableStateFlow(
        BikeAssociationState(supported = manager != null, bike = runCatching { readAssociation() }.getOrNull()),
    )

    val state: StateFlow<BikeAssociationState> = mutableState.asStateFlow()

    init {
        mutableState.value.bike?.let { bike ->
            bikeIdentityRepository.select(bike.address)
            ensurePresenceObservation()
        }
    }

    /**
     * Starts the system pairing picker. Success arrives through [onAssociated] or, on some
     * platform versions, through [acceptActivityResult].
     */
    fun associate(
        launchApproval: (IntentSender) -> Unit,
        onAssociated: (AssociatedBike) -> Unit,
        onFailure: (String) -> Unit,
    ) {
        val companionManager = manager ?: return onFailure("Companion device setup is unavailable on this phone")
        mutableState.update { it.copy(associationInProgress = true, errorMessage = null) }

        // The picker is scoped by the bike's name family and the HID-over-GATT service UUID
        // together, which is what separates the bike's LE interface from its BR/EDR audio
        // endpoint advertising under the same name; see BikeHogpServiceUuidString.
        val request = AssociationRequest.Builder()
            .addDeviceFilter(
                BluetoothLeDeviceFilter.Builder()
                    .setNamePattern(BikeNameFilter)
                    .setScanFilter(
                        ScanFilter.Builder().setServiceUuid(ParcelUuid.fromString(BikeHogpServiceUuidString)).build(),
                    )
                    .build(),
            )
            .setSingleDevice(false)
            // The watch profile is one of only two roles that grant the MANAGE_ONGOING_CALLS
            // app-op, which is the sole route by which Telecom binds a third-party
            // InCallService. The consent dialog will call the motorcycle a watch.
            .setDeviceProfile(AssociationRequest.DEVICE_PROFILE_WATCH)
            .build()

        val callback = object : CompanionDeviceManager.Callback() {
            override fun onAssociationPending(intentSender: IntentSender) = launchApproval(intentSender)

            override fun onAssociationCreated(associationInfo: AssociationInfo) {
                accept(associationInfo)?.let(onAssociated)
            }

            override fun onFailure(error: CharSequence?) = fail("Pairing canceled", onFailure)
        }
        runCatching { companionManager.associate(request, appContext.mainExecutor, callback) }
            .onFailure { fail("Couldn't start bike pairing. Try again.", onFailure) }
    }

    /** The Activity-result path out of the picker, for platforms that answer that way. */
    fun acceptActivityResult(resultCode: Int, data: Intent?): AssociatedBike? {
        if (resultCode != Activity.RESULT_OK || data == null) {
            mutableState.update { it.copy(associationInProgress = false) }
            return null
        }
        val bike = data.getParcelableExtra(CompanionDeviceManager.EXTRA_ASSOCIATION, AssociationInfo::class.java)
            ?.let(::accept)
        if (bike == null) {
            mutableState.update {
                it.copy(associationInProgress = false, errorMessage = "Couldn't read the selected bike. Try again.")
            }
        }
        return bike
    }

    /**
     * Re-reads the association. The rider can remove it from system settings without the app
     * being told; a refresh that cannot reach the service says nothing about the pairing, so
     * the current state is kept and only an error is surfaced.
     */
    fun refresh() {
        if (manager == null) return
        val current = runCatching { readAssociation() }.getOrElse { error ->
            Log.w(LogTag, "Could not read companion associations", error)
            mutableState.update {
                it.copy(associationInProgress = false, errorMessage = "Couldn't refresh the bike pairing.")
            }
            return
        }
        val previous = mutableState.value.bike
        if (previous != null && previous.address != current?.address) forgetPerBikeState(previous.address)
        current?.let { bikeIdentityRepository.select(it.address) }
        mutableState.update { state ->
            state.copy(
                bike = current,
                observingPresence = current != null && current == previous && state.observingPresence,
                associationInProgress = false,
                errorMessage = null,
            )
        }
        ensurePresenceObservation()
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
            companionManager.startObservingDevicePresence(
                ObservingDevicePresenceRequest.Builder().setAssociationId(bike.associationId).build(),
            )
        }
        mutableState.update {
            it.copy(
                observingPresence = result.isSuccess,
                errorMessage = if (result.isSuccess) null else "Couldn't enable automatic connection.",
            )
        }
    }

    /**
     * Removes the association, returning whether it was removed. Per-bike state is only cleared
     * once the system has confirmed the removal, so a failure leaves the pairing intact.
     */
    fun forget(): Boolean {
        val companionManager = manager ?: return false
        val bike = state.value.bike ?: return true
        runCatching {
            companionManager.stopObservingDevicePresence(
                ObservingDevicePresenceRequest.Builder().setAssociationId(bike.associationId).build(),
            )
        }
        val removed = runCatching { companionManager.disassociate(bike.associationId) }.isSuccess
        if (!removed) {
            mutableState.update { it.copy(observingPresence = false, errorMessage = "Couldn't forget the bike. Try again.") }
            ensurePresenceObservation()
            return false
        }
        forgetPerBikeState(bike.address)
        mutableState.value = BikeAssociationState(supported = true)
        return true
    }

    fun associatedBike(associationId: Int? = null): AssociatedBike? =
        state.value.bike?.takeIf { associationId == null || it.associationId == associationId }

    /**
     * Validates and records a device returned by the picker. The name is re-checked because a
     * peripheral outside this motorcycle family would be decoded with the wrong telemetry layout.
     */
    private fun accept(associationInfo: AssociationInfo): AssociatedBike? {
        val bike = associationInfo.toBike()
        if (bike == null || !bike.name.isApriliaBikeName()) {
            mutableState.update {
                it.copy(associationInProgress = false, errorMessage = "Choose an Aprilia RS 457 or Tuono 457.")
            }
            return null
        }
        val previous = mutableState.value.bike
        if (previous != null && previous.address != bike.address) forgetPerBikeState(previous.address)
        bikeIdentityRepository.select(bike.address)
        mutableState.update { it.copy(bike = bike, observingPresence = false, associationInProgress = false, errorMessage = null) }
        ensurePresenceObservation()
        return bike
    }

    /** The most recent association; the picker only ever offers this motorcycle family. */
    private fun readAssociation(): AssociatedBike? =
        manager?.myAssociations?.sortedBy(AssociationInfo::getId)?.lastOrNull { it.toBike() != null }?.toBike()

    private fun forgetPerBikeState(address: MacAddress) {
        protectionAcceptanceStore.clear(address)
        bikeIdentityRepository.clear(address)
    }

    private fun fail(message: String, onFailure: (String) -> Unit) {
        mutableState.update { it.copy(associationInProgress = false, errorMessage = message) }
        onFailure(message)
    }

    private companion object {
        const val LogTag = "BikeCompanionManager"
    }
}

/** The name comes from the scan record the picker matched, falling back to the display name. */
private fun AssociationInfo.toBike(): AssociatedBike? {
    val address = deviceMacAddress
        ?: associatedDevice?.bleDevice?.device?.address?.let { runCatching { MacAddress.fromString(it) }.getOrNull() }
        ?: return null
    val name = associatedDevice?.bleDevice?.scanRecord?.deviceName
        ?: displayName?.toString()?.takeIf(String::isNotBlank)
        ?: return null
    return AssociatedBike(address, name, id)
}
