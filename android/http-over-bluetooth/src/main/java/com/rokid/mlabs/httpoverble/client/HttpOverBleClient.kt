package com.rokid.mlabs.httpoverble.client

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import com.rokid.mlabs.httpoverble.common.HttpProxyServiceConstants
import com.rokid.mlabs.httpoverble.common.HttpRequest
import com.rokid.mlabs.httpoverble.common.HttpResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * HTTP over BLE Client implementation.
 * 
 * This client scans for and connects to BLE devices that implement the HTTP Proxy Service,
 * then sends HTTP requests over BLE and receives responses.
 * 
 * Usage:
 * ```kotlin
 * val client = HttpOverBleClient(context)
 * client.setCallback(object : HttpOverBleClientCallback {
 *     override fun onConnected(deviceAddress: String) { }
 *     override fun onDisconnected(deviceAddress: String) { }
 *     override fun onResponseReceived(response: HttpResponse) { }
 *     override fun onError(error: HttpOverBleError) { }
 *     override fun onServerFound(deviceAddress: String, deviceName: String?) { }
 * })
 * 
 * // Start scanning for servers
 * client.startScanning()
 * 
 * // Connect to a found server
 * client.connect(deviceAddress)
 * 
 * // Send HTTP request
 * client.sendRequest(HttpRequest(
 *     uri = "https://api.example.com/data",
 *     method = HttpMethod.GET,
 *     isHttps = true
 * ))
 * ```
 */
@SuppressLint("MissingPermission")
class HttpOverBleClient(private val context: Context) {
    
    companion object {
        private const val TAG = "HttpOverBleClient"
        private const val SCAN_TIMEOUT_MS = 10000L
        private const val CONNECTION_TIMEOUT_MS = 30000L
        private const val REQUEST_TIMEOUT_MS = 60000L
    }
    
    private val bluetoothManager: BluetoothManager = 
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var bluetoothGatt: BluetoothGatt? = null
    
    private var callback: HttpOverBleClientCallback? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private var isScanning = false
    private var isConnected = false
    
    // Characteristics
    private var uriCharacteristic: BluetoothGattCharacteristic? = null
    private var headersCharacteristic: BluetoothGattCharacteristic? = null
    private var statusCodeCharacteristic: BluetoothGattCharacteristic? = null
    private var bodyCharacteristic: BluetoothGattCharacteristic? = null
    private var controlPointCharacteristic: BluetoothGattCharacteristic? = null
    private var httpsSecurityCharacteristic: BluetoothGattCharacteristic? = null
    
    // Response building
    private var pendingStatusCode: Int = 0
    private var pendingHeaders: Map<String, String> = emptyMap()
    private var pendingBody: ByteArray? = null
    private var pendingHttps: Boolean = false
    private var pendingCertValidated: Boolean = false
    
    // Write queue for sequential characteristic writes
    private val writeQueue = ConcurrentLinkedQueue<WriteOperation>()
    private var isWriting = false
    
    private data class WriteOperation(
        val characteristic: BluetoothGattCharacteristic,
        val value: ByteArray
    )
    
    /**
     * Sets the callback for receiving client events.
     */
    fun setCallback(callback: HttpOverBleClientCallback) {
        this.callback = callback
    }
    
    /**
     * Checks if Bluetooth is enabled.
     */
    fun isBluetoothEnabled(): Boolean {
        return bluetoothAdapter?.isEnabled == true
    }
    
    /**
     * Starts scanning for HTTP Proxy Service servers.
     */
    fun startScanning() {
        if (!isBluetoothEnabled()) {
            callback?.onError(HttpOverBleError.BluetoothNotEnabled("Bluetooth is not enabled"))
            return
        }
        
        if (isScanning) {
            Log.w(TAG, "Already scanning")
            return
        }
        
        bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner
        if (bluetoothLeScanner == null) {
            callback?.onError(HttpOverBleError.BluetoothNotEnabled("BLE scanner not available"))
            return
        }
        
        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(HttpProxyServiceConstants.HTTP_PROXY_SERVICE_UUID))
                .build()
        )
        
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        
        try {
            bluetoothLeScanner?.startScan(filters, settings, scanCallback)
            isScanning = true
            Log.d(TAG, "Started scanning for HTTP Proxy Service devices")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start scanning", e)
            callback?.onError(HttpOverBleError.Unknown("Failed to start scanning: ${e.message}"))
        }
    }
    
    /**
     * Stops scanning for BLE devices.
     */
    fun stopScanning() {
        if (!isScanning) return
        
        try {
            bluetoothLeScanner?.stopScan(scanCallback)
            isScanning = false
            Log.d(TAG, "Stopped scanning")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop scanning", e)
        }
    }
    
    /**
     * Connects to a BLE device with the specified address.
     * @param deviceAddress The Bluetooth address of the device to connect to.
     */
    fun connect(deviceAddress: String) {
        if (!isBluetoothEnabled()) {
            callback?.onError(HttpOverBleError.BluetoothNotEnabled("Bluetooth is not enabled"))
            return
        }
        
        val device = bluetoothAdapter?.getRemoteDevice(deviceAddress)
        if (device == null) {
            callback?.onError(HttpOverBleError.ConnectionFailed("Device not found: $deviceAddress"))
            return
        }
        
        stopScanning()
        
        scope.launch {
            try {
                bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
                Log.d(TAG, "Connecting to device: $deviceAddress")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect to device", e)
                callback?.onError(HttpOverBleError.ConnectionFailed("Failed to connect: ${e.message}"))
            }
        }
    }
    
    /**
     * Disconnects from the currently connected BLE device.
     */
    fun disconnect() {
        bluetoothGatt?.let { gatt ->
            gatt.disconnect()
            gatt.close()
        }
        bluetoothGatt = null
        isConnected = false
        clearCharacteristics()
    }
    
    /**
     * Sends an HTTP request over BLE.
     * @param request The HTTP request to send.
     */
    fun sendRequest(request: HttpRequest) {
        if (!isConnected) {
            callback?.onError(HttpOverBleError.ConnectionFailed("Not connected to a server"))
            return
        }
        
        scope.launch {
            try {
                // Reset pending response data
                pendingStatusCode = 0
                pendingHeaders = emptyMap()
                pendingBody = null
                pendingHttps = request.isHttps
                pendingCertValidated = false
                
                // Write URI
                uriCharacteristic?.let { characteristic ->
                    val uriBytes = request.uri.toByteArray(Charsets.UTF_8)
                    queueWrite(characteristic, uriBytes)
                }
                
                // Write Headers if present
                if (request.headers.isNotEmpty()) {
                    headersCharacteristic?.let { characteristic ->
                        queueWrite(characteristic, request.serializeHeaders())
                    }
                }
                
                // Write Body if present
                request.body?.let { body ->
                    bodyCharacteristic?.let { characteristic ->
                        queueWrite(characteristic, body)
                    }
                }
                
                // Write Control Point to trigger the request
                controlPointCharacteristic?.let { characteristic ->
                    queueWrite(characteristic, byteArrayOf(request.getOpcode()))
                }
                
                Log.d(TAG, "Request queued: ${request.method} ${request.uri}")
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send request", e)
                callback?.onError(HttpOverBleError.CharacteristicWriteFailed("Failed to send request: ${e.message}"))
            }
        }
    }
    
    /**
     * Cancels the current HTTP request.
     */
    fun cancelRequest() {
        controlPointCharacteristic?.let { characteristic ->
            queueWrite(characteristic, byteArrayOf(HttpProxyServiceConstants.OPCODE_HTTP_REQUEST_CANCEL))
        }
    }
    
    private fun queueWrite(characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        writeQueue.add(WriteOperation(characteristic, value))
        processWriteQueue()
    }
    
    @Synchronized
    private fun processWriteQueue() {
        if (isWriting || writeQueue.isEmpty()) return
        
        val operation = writeQueue.poll() ?: return
        isWriting = true
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            bluetoothGatt?.writeCharacteristic(
                operation.characteristic,
                operation.value,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            )
        } else {
            @Suppress("DEPRECATION")
            operation.characteristic.value = operation.value
            @Suppress("DEPRECATION")
            bluetoothGatt?.writeCharacteristic(operation.characteristic)
        }
    }
    
    private fun clearCharacteristics() {
        uriCharacteristic = null
        headersCharacteristic = null
        statusCodeCharacteristic = null
        bodyCharacteristic = null
        controlPointCharacteristic = null
        httpsSecurityCharacteristic = null
    }
    
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            callback?.onServerFound(device.address, device.name)
        }
        
        override fun onBatchScanResults(results: List<ScanResult>) {
            results.forEach { result ->
                val device = result.device
                callback?.onServerFound(device.address, device.name)
            }
        }
        
        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed with error code: $errorCode")
            isScanning = false
            callback?.onError(HttpOverBleError.Unknown("Scan failed with error code: $errorCode"))
        }
    }
    
    private val gattCallback = object : BluetoothGattCallback() {
        
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "Connected to GATT server")
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "Disconnected from GATT server")
                    isConnected = false
                    callback?.onDisconnected(gatt.device.address)
                    clearCharacteristics()
                }
            }
        }
        
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Service discovery failed with status: $status")
                callback?.onError(HttpOverBleError.ServiceNotFound("Service discovery failed"))
                return
            }
            
            val service = gatt.getService(HttpProxyServiceConstants.HTTP_PROXY_SERVICE_UUID)
            if (service == null) {
                Log.e(TAG, "HTTP Proxy Service not found")
                callback?.onError(HttpOverBleError.ServiceNotFound("HTTP Proxy Service not found on device"))
                return
            }
            
            // Get all characteristics
            uriCharacteristic = service.getCharacteristic(HttpProxyServiceConstants.URI_CHARACTERISTIC_UUID)
            headersCharacteristic = service.getCharacteristic(HttpProxyServiceConstants.HTTP_HEADERS_CHARACTERISTIC_UUID)
            statusCodeCharacteristic = service.getCharacteristic(HttpProxyServiceConstants.HTTP_STATUS_CODE_CHARACTERISTIC_UUID)
            bodyCharacteristic = service.getCharacteristic(HttpProxyServiceConstants.HTTP_ENTITY_BODY_CHARACTERISTIC_UUID)
            controlPointCharacteristic = service.getCharacteristic(HttpProxyServiceConstants.HTTP_CONTROL_POINT_CHARACTERISTIC_UUID)
            httpsSecurityCharacteristic = service.getCharacteristic(HttpProxyServiceConstants.HTTPS_SECURITY_CHARACTERISTIC_UUID)
            val missing = mutableListOf<String>()
            if (uriCharacteristic == null) missing.add("URI")
            if (headersCharacteristic == null) missing.add("HTTP_HEADERS")
            if (statusCodeCharacteristic == null) missing.add("HTTP_STATUS_CODE")
            if (bodyCharacteristic == null) missing.add("HTTP_ENTITY_BODY")
            if (controlPointCharacteristic == null) missing.add("HTTP_CONTROL_POINT")
            if (httpsSecurityCharacteristic == null) missing.add("HTTPS_SECURITY")
            if (missing.isNotEmpty()) {
                Log.w(TAG, "Missing HPS characteristics: ${missing.joinToString(", ")}")
            } else {
                Log.d(TAG, "All HPS characteristics available")
            }
            
            // Enable notifications for status code characteristic to receive responses
            statusCodeCharacteristic?.let { characteristic ->
                gatt.setCharacteristicNotification(characteristic, true)
                val descriptor = characteristic.getDescriptor(HttpProxyServiceConstants.CLIENT_CHARACTERISTIC_CONFIG_UUID)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    gatt.writeDescriptor(descriptor)
                }
            }
            
            isConnected = true
            callback?.onConnected(gatt.device.address)
            Log.d(TAG, "HTTP Proxy Service discovered and configured")
        }
        
        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            isWriting = false
            
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Characteristic write failed: ${characteristic.uuid}")
                callback?.onError(HttpOverBleError.CharacteristicWriteFailed(
                    "Failed to write characteristic: ${characteristic.uuid}"
                ))
            }
            
            // Process next item in queue
            processWriteQueue()
        }
        
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            handleCharacteristicChanged(characteristic.uuid, characteristic.value)
        }
        
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleCharacteristicChanged(characteristic.uuid, value)
        }
        
        private fun handleCharacteristicChanged(uuid: java.util.UUID, value: ByteArray) {
            when (uuid) {
                HttpProxyServiceConstants.HTTP_STATUS_CODE_CHARACTERISTIC_UUID -> {
                    pendingStatusCode = HttpResponse.parseStatusCode(value)
                    // Read other response characteristics
                    readResponseCharacteristics()
                }
            }
        }
        
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                handleCharacteristicRead(characteristic.uuid, characteristic.value)
            }
        }
        
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                handleCharacteristicRead(characteristic.uuid, value)
            }
        }
        
        private fun handleCharacteristicRead(uuid: java.util.UUID, value: ByteArray) {
            when (uuid) {
                HttpProxyServiceConstants.HTTP_HEADERS_CHARACTERISTIC_UUID -> {
                    pendingHeaders = HttpResponse.parseHeaders(value)
                }
                HttpProxyServiceConstants.HTTP_ENTITY_BODY_CHARACTERISTIC_UUID -> {
                    pendingBody = value
                }
                HttpProxyServiceConstants.HTTPS_SECURITY_CHARACTERISTIC_UUID -> {
                    pendingCertValidated = value.isNotEmpty() && 
                        value[0] == HttpProxyServiceConstants.HTTPS_SECURITY_CERTIFICATE_VALIDATED
                }
            }
            
            // Check if we have all response data
            if (pendingStatusCode > 0) {
                val response = HttpResponse(
                    statusCode = pendingStatusCode,
                    headers = pendingHeaders,
                    body = pendingBody,
                    isHttps = pendingHttps,
                    certificateValidated = pendingCertValidated
                )
                callback?.onResponseReceived(response)
            }
        }
    }
    
    private fun readResponseCharacteristics() {
        bluetoothGatt?.let { gatt ->
            headersCharacteristic?.let { gatt.readCharacteristic(it) }
            bodyCharacteristic?.let { gatt.readCharacteristic(it) }
            if (pendingHttps) {
                httpsSecurityCharacteristic?.let { gatt.readCharacteristic(it) }
            }
        }
    }
    
    /**
     * Releases all resources held by this client.
     */
    fun close() {
        stopScanning()
        disconnect()
    }
}
