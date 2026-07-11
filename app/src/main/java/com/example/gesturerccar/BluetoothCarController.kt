package com.example.gesturerccar

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.IOException
import java.io.OutputStream
import java.util.UUID

/**
 * Talks to a classic Bluetooth SPP module (HC-05 / HC-06) over RFCOMM.
 *
 * Handles scanning (startDiscovery) and pairing (createBond) itself, in-app,
 * instead of requiring the device to already be paired via Android's system
 * Settings > Bluetooth screen. This works around a known Android restriction
 * where many phones hide or refuse to pair "headless" SPP-only devices like
 * the HC-05 from the system Settings UI - the underlying Bluetooth stack
 * still supports them fine, apps just need to trigger discovery/bonding
 * programmatically instead of relying on that menu.
 */
class BluetoothCarController(private val context: Context) {

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }

    private var socket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private var connectedDevice: BluetoothDevice? = null

    private var discoveryReceiver: BroadcastReceiver? = null
    private var bondReceiver: BroadcastReceiver? = null
    private val bondTimeoutHandler = Handler(Looper.getMainLooper())

    @SuppressLint("MissingPermission")
    fun pairedDevices(): List<BluetoothDevice> {
        return bluetoothAdapter?.bondedDevices?.toList() ?: emptyList()
    }

    /**
     * Scans for nearby Bluetooth devices, paired or not. [onDeviceFound] fires once per
     * newly-seen device (including ones Android has never paired before, like a fresh
     * HC-05); [onFinished] fires when the scan finishes naturally (~12 seconds). Call
     * [stopDiscovery] to cancel early, and [stopScanReceiver] once the picker UI closes.
     */
    @SuppressLint("MissingPermission")
    fun startDiscovery(onDeviceFound: (BluetoothDevice) -> Unit, onFinished: () -> Unit) {
        stopScanReceiver()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothDevice.ACTION_FOUND -> getDeviceExtra(intent)?.let(onDeviceFound)
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> onFinished()
                }
            }
        }
        discoveryReceiver = receiver
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        registerReceiverCompat(receiver, filter)
        bluetoothAdapter?.cancelDiscovery()
        bluetoothAdapter?.startDiscovery()
    }

    @SuppressLint("MissingPermission")
    fun stopDiscovery() {
        bluetoothAdapter?.cancelDiscovery()
    }

    /** Unregisters the discovery broadcast receiver. Safe to call even if not scanning. */
    fun stopScanReceiver() {
        discoveryReceiver?.let {
            try { context.unregisterReceiver(it) } catch (_: IllegalArgumentException) { }
        }
        discoveryReceiver = null
    }

    /**
     * Pairs with [device] if it isn't bonded yet, then reports success/failure via
     * [onResult]. This is the in-app replacement for Settings > Bluetooth > Pair new
     * device - Android will still show its normal PIN-entry prompt (usually 1234 or
     * 0000 for these modules), this just triggers that flow directly instead of going
     * through the system device list.
     */
    @SuppressLint("MissingPermission")
    fun createBond(device: BluetoothDevice, onResult: (Boolean) -> Unit) {
        if (device.bondState == BluetoothDevice.BOND_BONDED) {
            onResult(true)
            return
        }

        bondReceiver?.let {
            try { context.unregisterReceiver(it) } catch (_: IllegalArgumentException) { }
        }
        bondTimeoutHandler.removeCallbacksAndMessages(null)

        var finished = false

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
                val changedDevice = getDeviceExtra(intent) ?: return
                if (changedDevice.address != device.address) return
                val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                if (state == BluetoothDevice.BOND_BONDING) return // still in progress

                if (!finished) {
                    finished = true
                    bondTimeoutHandler.removeCallbacksAndMessages(null)
                    try { context.unregisterReceiver(this) } catch (_: IllegalArgumentException) { }
                    bondReceiver = null
                    onResult(state == BluetoothDevice.BOND_BONDED)
                }
            }
        }
        bondReceiver = receiver
        registerReceiverCompat(receiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))

        // Safety net: if the user ignores/dismisses the system PIN prompt, don't hang forever.
        bondTimeoutHandler.postDelayed({
            if (!finished) {
                finished = true
                bondReceiver?.let {
                    try { context.unregisterReceiver(it) } catch (_: IllegalArgumentException) { }
                }
                bondReceiver = null
                onResult(false)
            }
        }, BOND_TIMEOUT_MS)

        val started = device.createBond()
        if (!started) {
            finished = true
            bondTimeoutHandler.removeCallbacksAndMessages(null)
            try { context.unregisterReceiver(receiver) } catch (_: IllegalArgumentException) { }
            bondReceiver = null
            onResult(false)
        }
    }

    @Suppress("DEPRECATION")
    private fun getDeviceExtra(intent: Intent): BluetoothDevice? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
    }

    private fun registerReceiverCompat(receiver: BroadcastReceiver, filter: IntentFilter) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
    }

    /** Blocking call - run this off the main thread. Returns true on success. */
    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice): Boolean {
        disconnect()
        return try {
            val sock = device.createRfcommSocketToServiceRecord(SPP_UUID)
            bluetoothAdapter?.cancelDiscovery()
            sock.connect()
            socket = sock
            outputStream = sock.outputStream
            connectedDevice = device
            true
        } catch (e: IOException) {
            Log.e(TAG, "Bluetooth connect failed", e)
            disconnect()
            false
        }
    }

    fun isConnected(): Boolean = socket?.isConnected == true

    @SuppressLint("MissingPermission")
    fun connectedDeviceName(): String? = connectedDevice?.name

    /** Sends a single character command byte, e.g. 'F', 'B', 'L', 'R', 'S'. */
    fun send(command: Char) {
        try {
            outputStream?.write(command.code)
            outputStream?.flush()
        } catch (e: IOException) {
            Log.e(TAG, "Send failed", e)
        }
    }

    fun disconnect() {
        try { outputStream?.close() } catch (_: IOException) { }
        try { socket?.close() } catch (_: IOException) { }
        outputStream = null
        socket = null
        connectedDevice = null
    }

    /** Call from onDestroy so scan/bond receivers never leak past the Activity's lifetime. */
    fun release() {
        stopDiscovery()
        stopScanReceiver()
        bondReceiver?.let {
            try { context.unregisterReceiver(it) } catch (_: IllegalArgumentException) { }
        }
        bondReceiver = null
        bondTimeoutHandler.removeCallbacksAndMessages(null)
        disconnect()
    }

    companion object {
        private const val TAG = "BluetoothCarController"
        private const val BOND_TIMEOUT_MS = 15000L
        // Standard Serial Port Profile UUID - this is what HC-05/HC-06 modules use.
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
