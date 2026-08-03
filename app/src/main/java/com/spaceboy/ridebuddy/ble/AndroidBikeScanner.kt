package com.spaceboy.ridebuddy.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.core.util.size
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AndroidBikeScanner(context: Context) {
    private val bluetoothAdapter = context.getSystemService(BluetoothManager::class.java).adapter
    private val mainHandler = Handler(Looper.getMainLooper())
    private val stopRunnable = Runnable(::stopInternal)
    private val mutableScanState = MutableStateFlow<BikeScanState>(BikeScanState.Idle)
    private val mutableBikes = MutableStateFlow<List<DiscoveredBike>>(emptyList())
    private var activeCallback: ScanCallback? = null

    val scanState: StateFlow<BikeScanState> = mutableScanState.asStateFlow()
    val bikes: StateFlow<List<DiscoveredBike>> = mutableBikes.asStateFlow()

    @SuppressLint("MissingPermission")
    fun start() {
        mainHandler.post(::startInternal)
    }

    @SuppressLint("MissingPermission")
    private fun startInternal() {
        if (!bluetoothAdapter.isEnabled) {
            mutableScanState.value = BikeScanState.Failed("Turn on Bluetooth to find your bike")
            return
        }

        stopInternal()
        mutableBikes.value = emptyList()
        val scanner = bluetoothAdapter.bluetoothLeScanner
        if (scanner == null) {
            mutableScanState.value = BikeScanState.Failed("Bluetooth LE is not available")
            return
        }

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val advertisedName = result.scanRecord?.deviceName ?: return
                if (!advertisedName.isApriliaBikeName()) {
                    return
                }

                val bike = DiscoveredBike(
                    name = advertisedName,
                    address = result.device.address,
                    rssi = result.rssi,
                    serviceUuids = result.scanRecord?.serviceUuids.orEmpty().map { it.uuid.toString() },
                    manufacturerDataHex = result.scanRecord?.manufacturerSpecificData?.let { data ->
                        buildList {
                            for (index in 0 until data.size) add(data.valueAt(index).toHex())
                        }.joinToString()
                    },
                )
                val current = mutableBikes.value.associateBy(DiscoveredBike::address).toMutableMap()
                current[bike.address] = bike
                mutableBikes.value = current.values.sortedByDescending(DiscoveredBike::rssi)
            }

            override fun onScanFailed(errorCode: Int) {
                activeCallback = null
                mutableScanState.value = BikeScanState.Failed("Bluetooth scan failed ($errorCode)")
            }
        }

        activeCallback = callback
        mutableScanState.value = BikeScanState.Scanning
        scanner.startScan(
            null,
            ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build(),
            callback,
        )
        mainHandler.postDelayed(stopRunnable, ScanDurationMillis)
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        mainHandler.post(::stopInternal)
    }

    @SuppressLint("MissingPermission")
    private fun stopInternal() {
        mainHandler.removeCallbacks(stopRunnable)
        val callback = activeCallback ?: return
        bluetoothAdapter.bluetoothLeScanner?.stopScan(callback)
        activeCallback = null
        mutableScanState.value = BikeScanState.Complete
    }

    private companion object {
        const val ScanDurationMillis = 12_000L
    }
}

data class DiscoveredBike(
    val name: String,
    val address: String,
    val rssi: Int,
    val serviceUuids: List<String> = emptyList(),
    val manufacturerDataHex: String? = null,
) {
    val addressSuffix: String
        get() = address.takeLast(5)
}



sealed interface BikeScanState {
    data object Idle : BikeScanState
    data object Scanning : BikeScanState
    data object Complete : BikeScanState
    data class Failed(val message: String) : BikeScanState
}
