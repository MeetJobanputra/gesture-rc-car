package com.example.gesturerccar

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import java.io.IOException
import java.io.OutputStream
import java.util.UUID

/**
 * Talks to a classic Bluetooth SPP module (HC-05 / HC-06) over RFCOMM.
 *
 * IMPORTANT: the module must already be paired with the phone via Android's
 * system Bluetooth settings (Settings > Bluetooth > Pair new device) before
 * this app can connect to it - this class only connects to already-paired
 * devices, it doesn't do the initial pairing/PIN entry itself.
 */
class BluetoothCarController(private val context: Context) {

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }

    private var socket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private var connectedDevice: BluetoothDevice? = null

    @SuppressLint("MissingPermission")
    fun pairedDevices(): List<BluetoothDevice> {
        return bluetoothAdapter?.bondedDevices?.toList() ?: emptyList()
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

    companion object {
        private const val TAG = "BluetoothCarController"
        // Standard Serial Port Profile UUID - this is what HC-05/HC-06 modules use.
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
