package com.rokid.mlabs.httpoverble.sample

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.rokid.mlabs.httpoverble.client.HttpOverBleClient
import com.rokid.mlabs.httpoverble.client.HttpOverBleClientCallback
import com.rokid.mlabs.httpoverble.client.HttpOverBleError
import com.rokid.mlabs.httpoverble.common.HttpMethod
import com.rokid.mlabs.httpoverble.common.HttpRequest
import com.rokid.mlabs.httpoverble.common.HttpResponse
import com.rokid.mlabs.httpoverble.server.HttpOverBleServer
import com.rokid.mlabs.httpoverble.server.HttpOverBleServerCallback
import com.rokid.mlabs.httpoverble.server.HttpOverBleServerError

/**
 * Sample activity demonstrating the HTTP over Bluetooth library.
 * 
 * This sample allows users to:
 * - Run as a BLE server that receives HTTP requests and executes them
 * - Run as a BLE client that sends HTTP requests to a BLE server
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "HttpOverBleSample"
    }

    private lateinit var statusTextView: TextView
    private lateinit var logTextView: TextView
    private lateinit var modeRadioGroup: RadioGroup
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var sendRequestButton: Button

    private var bleServer: HttpOverBleServer? = null
    private var bleClient: HttpOverBleClient? = null
    
    private var isServerMode = true
    private var isRunning = false
    private var connectedDeviceAddress: String? = null

    // Permission request launcher
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        if (allGranted) {
            appendLog("All permissions granted")
            if (isServerMode) {
                startServer()
            } else {
                startClient()
            }
        } else {
            appendLog("Some permissions were denied")
            Toast.makeText(this, "Bluetooth permissions are required", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupListeners()
        updateUI()
    }

    private fun initViews() {
        statusTextView = findViewById(R.id.statusTextView)
        logTextView = findViewById(R.id.logTextView)
        modeRadioGroup = findViewById(R.id.modeRadioGroup)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        sendRequestButton = findViewById(R.id.sendRequestButton)
    }

    private fun setupListeners() {
        modeRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            isServerMode = checkedId == R.id.serverModeRadio
            updateUI()
        }

        startButton.setOnClickListener {
            checkPermissionsAndStart()
        }

        stopButton.setOnClickListener {
            stop()
        }

        sendRequestButton.setOnClickListener {
            sendTestRequest()
        }
    }

    private fun checkPermissionsAndStart() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }

        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isEmpty()) {
            if (isServerMode) {
                startServer()
            } else {
                startClient()
            }
        } else {
            requestPermissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    private fun startServer() {
        appendLog("Starting BLE Server...")
        
        bleServer = HttpOverBleServer(this).apply {
            setCallback(serverCallback)
            start()
        }
        
        isRunning = true
        updateUI()
    }

    private fun startClient() {
        appendLog("Starting BLE Client and scanning for servers...")
        
        bleClient = HttpOverBleClient(this).apply {
            setCallback(clientCallback)
            startScanning()
        }
        
        isRunning = true
        updateUI()
    }

    private fun stop() {
        if (isServerMode) {
            bleServer?.close()
            bleServer = null
            appendLog("Server stopped")
        } else {
            bleClient?.close()
            bleClient = null
            connectedDeviceAddress = null
            appendLog("Client stopped")
        }
        
        isRunning = false
        updateUI()
    }

    private fun sendTestRequest() {
        val client = bleClient ?: return
        
        if (connectedDeviceAddress == null) {
            Toast.makeText(this, "Not connected to a server", Toast.LENGTH_SHORT).show()
            return
        }

        appendLog("Sending test HTTPS request...")
        
        val request = HttpRequest(
            uri = "https://httpbin.org/get",
            method = HttpMethod.GET,
            headers = mapOf("Accept" to "application/json"),
            isHttps = true
        )
        
        client.sendRequest(request)
    }

    private fun updateUI() {
        val modeText = if (isServerMode) "Server Mode" else "Client Mode"
        val statusText = when {
            !isRunning -> "$modeText - Stopped"
            isServerMode -> "$modeText - Advertising"
            connectedDeviceAddress != null -> "$modeText - Connected to $connectedDeviceAddress"
            else -> "$modeText - Scanning..."
        }
        
        statusTextView.text = statusText
        
        startButton.isEnabled = !isRunning
        stopButton.isEnabled = isRunning
        modeRadioGroup.isEnabled = !isRunning
        sendRequestButton.isEnabled = isRunning && !isServerMode && connectedDeviceAddress != null
        
        for (i in 0 until modeRadioGroup.childCount) {
            modeRadioGroup.getChildAt(i).isEnabled = !isRunning
        }
    }

    private fun appendLog(message: String) {
        Log.d(TAG, message)
        runOnUiThread {
            val currentText = logTextView.text.toString()
            val newText = if (currentText.isEmpty()) {
                message
            } else {
                "$currentText\n$message"
            }
            logTextView.text = newText
        }
    }

    // Server callbacks
    private val serverCallback = object : HttpOverBleServerCallback {
        override fun onServerStarted() {
            appendLog("Server started - advertising HTTP Proxy Service")
        }

        override fun onServerStopped() {
            appendLog("Server stopped")
        }

        override fun onClientConnected(deviceAddress: String) {
            appendLog("Client connected: $deviceAddress")
        }

        override fun onClientDisconnected(deviceAddress: String) {
            appendLog("Client disconnected: $deviceAddress")
        }

        override fun onRequestReceived(request: HttpRequest, clientAddress: String) {
            appendLog("Request received from $clientAddress: ${request.method} ${request.uri}")
        }

        override fun onResponseSent(response: HttpResponse, clientAddress: String) {
            appendLog("Response sent to $clientAddress: ${response.statusCode}")
        }

        override fun onError(error: HttpOverBleServerError) {
            appendLog("Server error: ${error.message}")
        }
    }

    // Client callbacks
    private val clientCallback = object : HttpOverBleClientCallback {
        override fun onServerFound(deviceAddress: String, deviceName: String?) {
            appendLog("Found server: $deviceAddress (${deviceName ?: "Unknown"})")
            // Auto-connect to the first server found
            if (connectedDeviceAddress == null) {
                appendLog("Connecting to $deviceAddress...")
                bleClient?.connect(deviceAddress)
            }
        }

        override fun onConnected(deviceAddress: String) {
            connectedDeviceAddress = deviceAddress
            appendLog("Connected to server: $deviceAddress")
            runOnUiThread { updateUI() }
        }

        override fun onDisconnected(deviceAddress: String) {
            connectedDeviceAddress = null
            appendLog("Disconnected from server: $deviceAddress")
            runOnUiThread { updateUI() }
        }

        override fun onResponseReceived(response: HttpResponse) {
            val bodyPreview = response.body?.let { 
                String(it, Charsets.UTF_8).take(200) 
            } ?: "(empty)"
            appendLog("Response received: ${response.statusCode}")
            appendLog("Body preview: $bodyPreview")
        }

        override fun onError(error: HttpOverBleError) {
            appendLog("Client error: ${error.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        bleServer?.close()
        bleClient?.close()
    }
}
