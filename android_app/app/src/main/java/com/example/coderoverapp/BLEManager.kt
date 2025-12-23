package com.example.coderoverapp.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.*
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.Locale

@SuppressLint("MissingPermission")
class BLEManager(private val context: Context) {
    companion object {
        private const val TAG = "BLEManager"
        private val SERVICE_UUID = UUID.fromString("12345678-1234-1234-1234-1234567890ab")
        private val CHAR_UUID = UUID.fromString("abcd1234-1234-1234-1234-abcdef123456")
    }

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val bm = (context.getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager)
        bm.adapter
    }

    private var bluetoothGatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null

    // Exposed state (observe this from UI)
    val isConnectedState = mutableStateOf(false)

    // Scanning helpers
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private var scanCallback: ScanCallback? = null
    private var scanning = false
    private var scheduledStopFuture: ScheduledFuture<*>? = null
    private var scheduledRetryFuture: ScheduledFuture<*>? = null
    private var bleScanner: BluetoothLeScanner? = null

    // Public: start scanning and retry until connected (or until stopScanning called)
    @SuppressLint("MissingPermission")
    fun startScanningWithRetry(scanWindowMs: Long = 10000L, retryDelayMs: Long = 1000L) {
        // If already connected, nothing to do
        if (isConnectedState.value) {
            Log.d(TAG, "Already connected - skipping scan")
            return
        }

        val adapter = bluetoothAdapter ?: run {
            Log.e(TAG, "BluetoothAdapter null")
            return
        }
        val scanner = adapter.bluetoothLeScanner ?: run {
            Log.e(TAG, "LeScanner null")
            return
        }
        bleScanner = scanner

        // If already scanning, no-op
        if (scanning) {
            Log.d(TAG, "Already scanning")
            return
        }

        // Create filter for your service UUID — faster and more reliable
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY) // while in foreground
            .build()

        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                Log.d(TAG, "Scan result: ${device.address} / ${device.name} / rssi=${result.rssi}")
                // Immediately stop scanning and connect
                stopScanInternal()
                connectToDevice(device)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { r ->
                    Log.d(TAG, "Batch result: ${r.device.address} / ${r.device.name} / rssi=${r.rssi}")
                }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "Scan failed: $errorCode")
                stopScanInternal()
                // schedule a retry
                scheduleRetry(scanWindowMs, retryDelayMs)
            }
        }

        try {
            Log.d(TAG, "Starting BLE scan (filter service=$SERVICE_UUID)")
            scanner.startScan(listOf(filter), settings, scanCallback)
            scanning = true

            // Schedule stop after scanWindowMs, and if not connected -> schedule retry
            scheduledStopFuture?.cancel(true)
            scheduledStopFuture = executor.schedule({
                try {
                    Log.d(TAG, "Scan window expired -> stopping scan")
                    stopScanInternal()
                    // only schedule a retry if still not connected
                    if (!isConnectedState.value) {
                        scheduleRetry(scanWindowMs, retryDelayMs)
                    }
                } catch (ex: Exception) {
                    Log.e(TAG, "Error stopping scan: ${ex.message}")
                }
            }, scanWindowMs, TimeUnit.MILLISECONDS)
        } catch (ex: Exception) {
            Log.e(TAG, "Failed to start scan: ${ex.message}")
            // schedule retry a bit later
            scheduleRetry(scanWindowMs, retryDelayMs)
        }
    }

    private fun scheduleRetry(scanWindowMs: Long, retryDelayMs: Long) {
        // Cancel previous retry if any
        scheduledRetryFuture?.cancel(true)
        Log.d(TAG, "Scheduling scan retry in ${retryDelayMs}ms")
        scheduledRetryFuture = executor.schedule({
            if (!isConnectedState.value) {
                startScanningWithRetry(scanWindowMs, retryDelayMs)
            } else {
                Log.d(TAG, "Connected while waiting for retry -> abort retry")
            }
        }, retryDelayMs, TimeUnit.MILLISECONDS)
    }

    @SuppressLint("MissingPermission")
    private fun stopScanInternal() {
        try {
            val scanner = bleScanner ?: bluetoothAdapter?.bluetoothLeScanner
            scanCallback?.let { cb ->
                try {
                    scanner?.stopScan(cb)
                    Log.d(TAG, "stopScanInternal: stopped")
                } catch (e: Exception) {
                    Log.e(TAG, "stopScanInternal failed: ${e.message}")
                }
            }
        } finally {
            scheduledStopFuture?.cancel(true)
            scheduledStopFuture = null
            scanCallback = null
            scanning = false
        }
    }

    // Public stop: cancel scheduled retries and stop current scan
    fun stopScanning() {
        Log.d(TAG, "stopScanning requested")
        scheduledRetryFuture?.cancel(true)
        scheduledRetryFuture = null
        stopScanInternal()
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        Log.d(TAG, "Connecting to ${device.address} / ${device.name}")
        // Make sure scanning is stopped
        stopScanInternal()
        // Autoconnect = false
        bluetoothGatt = device.connectGatt(context, false, gattCallback)
    }

    private val gattCallback = object : android.bluetooth.BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            super.onConnectionStateChange(gatt, status, newState)
            Log.d(TAG, "onConnectionStateChange status=$status newState=$newState")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "Connected -> discovering services")
                isConnectedState.value = true
                // cancel any scheduled retries
                scheduledRetryFuture?.cancel(true)
                scheduledRetryFuture = null
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "Disconnected")
                isConnectedState.value = false
                writeCharacteristic = null
                try {
                    bluetoothGatt?.close()
                } catch (ignored: Exception) {}
                bluetoothGatt = null

                // start scanning again automatically
                startScanningWithRetry()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            super.onServicesDiscovered(gatt, status)
            Log.d(TAG, "Services discovered: status=$status")
            val service = gatt.getService(SERVICE_UUID)
            if (service == null) {
                Log.e(TAG, "Service not found on device")
                return
            }
            val chr = service.getCharacteristic(CHAR_UUID)
            if (chr == null) {
                Log.e(TAG, "Characteristic not found")
                return
            }
            chr.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            writeCharacteristic = chr
            bluetoothGatt = gatt
            Log.d(TAG, "Characteristic ready for writes")
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            super.onCharacteristicWrite(gatt, characteristic, status)
            Log.d(TAG, "onCharacteristicWrite status=$status")
        }
    }

    @SuppressLint("MissingPermission")
    fun sendDrive(vLeft: Double, vRight: Double, honk: Int = 0): Boolean {
        val chr = writeCharacteristic ?: run {
            Log.w(TAG, "sendDrive: characteristic not ready")
            return false
        }
        val payload = String.format(Locale.US, "%.3f, %.3f, %d", vLeft, vRight, honk)
        Log.d(TAG, "sendDrive -> $payload")
        chr.value = payload.toByteArray(Charsets.UTF_8)
        return try {
            val ok = bluetoothGatt?.writeCharacteristic(chr) ?: false
            Log.d(TAG, "writeCharacteristic called -> $ok")
            ok
        } catch (e: Exception) {
            Log.e(TAG, "write failed: ${e.message}")
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        Log.d(TAG, "disconnect() called")
        stopScanning()
        bluetoothGatt?.let {
            try {
                it.disconnect()
                it.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error during disconnect: ${e.message}")
            }
        }
        writeCharacteristic = null
        bluetoothGatt = null
        isConnectedState.value = false
    }
}
