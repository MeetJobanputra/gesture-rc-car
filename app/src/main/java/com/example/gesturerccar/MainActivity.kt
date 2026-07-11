package com.example.gesturerccar

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.example.gesturerccar.databinding.ActivityMainBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), GestureRecognizerHelper.GestureResultListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var gestureHelper: GestureRecognizerHelper
    private lateinit var bluetoothController: BluetoothCarController
    private lateinit var cameraExecutor: ExecutorService

    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastSentCommand: Char? = null
    private var lastGestureSeenAt = 0L

    // ============================================================
    // GESTURE -> CAR COMMAND MAPPING
    // Edit the characters on the right to match whatever your Arduino
    // sketch already expects for each direction. F/B/L/R/S is the most
    // common convention in Arduino BT-car tutorials, but if yours is
    // different (e.g. lowercase, or different letters), change it here.
    // ============================================================
    private val gestureCommandMap = mapOf(
        "Thumb_Up" to 'F',    // 👍  Forward
        "Thumb_Down" to 'B',  // 👎  Backward
        "Closed_Fist" to 'S', // ✊  Stop
        "Open_Palm" to 'S',   // ✋  Stop (redundant, extra safety)
        "Victory" to 'L',     // ✌️  Left
        "ILoveYou" to 'R'     // 🤟  Right
    )
    private val STOP_COMMAND = 'S'

    // If no confident gesture is seen for this long, auto-send Stop.
    // Prevents the car from driving away if it loses sight of your hand.
    private val NO_HAND_TIMEOUT_MS = 800L
    private val MIN_GESTURE_SCORE = 0.6f

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (perms.values.all { it }) {
            startCamera()
        } else {
            binding.statusLabel.text = "Camera/Bluetooth permission denied"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()
        bluetoothController = BluetoothCarController(this)
        gestureHelper = GestureRecognizerHelper(context = this, listener = this)

        binding.connectButton.setOnClickListener { showDevicePicker() }

        requestPermissionLauncher.launch(requiredPermissions())

        mainHandler.post(watchdog)
    }

    // Safety watchdog - runs continuously, forces a Stop if we've lost the hand.
    private val watchdog = object : Runnable {
        override fun run() {
            if (bluetoothController.isConnected() &&
                System.currentTimeMillis() - lastGestureSeenAt > NO_HAND_TIMEOUT_MS &&
                lastSentCommand != STOP_COMMAND
            ) {
                sendCommand(STOP_COMMAND)
            }
            mainHandler.postDelayed(this, 300)
        }
    }

    private fun requiredPermissions(): Array<String> {
        val perms = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return perms.toTypedArray()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }

            val imageAnalyzer = ImageAnalysis.Builder()
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        gestureHelper.recognizeLiveStream(imageProxy)
                    }
                }

            // Front camera so you can see yourself gesturing while looking at the screen.
            // Switch to CameraSelector.DEFAULT_BACK_CAMERA if you prefer.
            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
            } catch (e: Exception) {
                Log.e(TAG, "Camera bind failed", e)
                binding.statusLabel.text = "Camera error: ${e.message}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onGestureResult(categoryName: String?, score: Float, timestampMs: Long) {
        runOnUiThread {
            if (categoryName != null && categoryName != "None" && score >= MIN_GESTURE_SCORE) {
                lastGestureSeenAt = System.currentTimeMillis()
                binding.gestureLabel.text = "$categoryName  (${(score * 100).toInt()}%)"

                val command = gestureCommandMap[categoryName]
                if (command != null && command != lastSentCommand) {
                    sendCommand(command)
                }
            } else {
                binding.gestureLabel.text = "Show a gesture"
            }
        }
    }

    override fun onError(error: String) {
        runOnUiThread { binding.statusLabel.text = "Gesture error: $error" }
        Log.e(TAG, error)
    }

    private fun sendCommand(command: Char) {
        lastSentCommand = command
        bluetoothController.send(command)
        val deviceName = bluetoothController.connectedDeviceName() ?: "not connected"
        binding.statusLabel.text = "Sent: $command   ($deviceName)"
    }

    // Shows already-paired devices immediately, then scans (startDiscovery) for
    // any nearby unpaired ones too - e.g. a fresh HC-05 that Android's Settings
    // app won't let you pair from directly. Tapping an unpaired entry pairs with
    // it in-app (createBond) before connecting; tapping an already-paired one
    // connects right away, same as before.
    @SuppressLint("MissingPermission")
    private fun showDevicePicker() {
        val btPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            Manifest.permission.BLUETOOTH_CONNECT else Manifest.permission.ACCESS_FINE_LOCATION

        if (ContextCompat.checkSelfPermission(this, btPermission) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(requiredPermissions())
            return
        }

        fun labelFor(d: BluetoothDevice): String {
            val name = d.name ?: d.address
            return if (d.bondState == BluetoothDevice.BOND_BONDED) name else "$name  (tap to pair)"
        }

        val devices = mutableListOf<BluetoothDevice>()
        val seenAddresses = mutableSetOf<String>()
        bluetoothController.pairedDevices().forEach {
            devices.add(it)
            seenAddresses.add(it.address)
        }

        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_1,
            devices.map { labelFor(it) }.toMutableList()
        )

        val dialog = AlertDialog.Builder(this)
            .setTitle("Select your car's Bluetooth module (scanning\u2026)")
            .setAdapter(adapter) { _, which -> connectTo(devices[which]) }
            .setNegativeButton("Cancel", null)
            .setOnDismissListener { bluetoothController.stopDiscovery() }
            .show()

        bluetoothController.startDiscovery(
            onDeviceFound = { device ->
                if (seenAddresses.add(device.address)) {
                    runOnUiThread {
                        devices.add(device)
                        adapter.add(labelFor(device))
                    }
                }
            },
            onFinished = {
                runOnUiThread { dialog.setTitle("Select your car's Bluetooth module") }
            }
        )
    }

    @SuppressLint("MissingPermission")
    private fun connectTo(device: BluetoothDevice) {
        bluetoothController.stopDiscovery()

        if (device.bondState == BluetoothDevice.BOND_BONDED) {
            finishConnect(device)
            return
        }

        binding.statusLabel.text = "Pairing with ${device.name ?: device.address}\u2026 check for a PIN prompt"
        bluetoothController.createBond(device) { bonded ->
            runOnUiThread {
                if (bonded) {
                    finishConnect(device)
                } else {
                    binding.statusLabel.text = "Pairing failed or was cancelled - tap Connect to Car to try again"
                }
            }
        }
    }

    private fun finishConnect(device: BluetoothDevice) {
        binding.statusLabel.text = "Connecting..."
        Thread {
            val ok = bluetoothController.connect(device)
            runOnUiThread {
                binding.statusLabel.text = if (ok) {
                    "Connected to ${bluetoothController.connectedDeviceName()}"
                } else {
                    "Connection failed - is it already connected elsewhere?"
                }
            }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacks(watchdog)
        cameraExecutor.shutdown()
        gestureHelper.close()
        bluetoothController.release()
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
