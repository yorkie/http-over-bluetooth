package com.example.httpoverble.server

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import com.example.httpoverble.common.HttpMethod
import com.example.httpoverble.common.HttpProxyServiceConstants
import com.example.httpoverble.common.HttpRequest
import com.example.httpoverble.common.HttpResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.Headers.Companion.toHeaders
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Arrays
import java.util.concurrent.TimeUnit

/**
 * HTTP over BLE Server implementation.
 * 
 * This server advertises the HTTP Proxy Service, receives HTTP requests from BLE clients,
 * executes them over WiFi/5G network, and sends responses back to the clients.
 * 
 * Usage:
 * ```kotlin
 * val server = HttpOverBleServer(context)
 * server.setCallback(object : HttpOverBleServerCallback {
 *     override fun onServerStarted() { }
 *     override fun onServerStopped() { }
 *     override fun onClientConnected(deviceAddress: String) { }
 *     override fun onClientDisconnected(deviceAddress: String) { }
 *     override fun onRequestReceived(request: HttpRequest, clientAddress: String) { }
 *     override fun onResponseSent(response: HttpResponse, clientAddress: String) { }
 *     override fun onError(error: HttpOverBleServerError) { }
 * })
 * 
 * // Start the server
 * server.start()
 * 
 * // Stop the server
 * server.stop()
 * ```
 */
@SuppressLint("MissingPermission")
class HttpOverBleServer(private val context: Context) {
    
    companion object {
        private const val TAG = "HttpOverBleServer"
        private const val HTTP_TIMEOUT_SECONDS = 30L
    }
    
    private val bluetoothManager: BluetoothManager = 
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private var bluetoothLeAdvertiser: BluetoothLeAdvertiser? = null
    private var gattServer: BluetoothGattServer? = null
    
    private var callback: HttpOverBleServerCallback? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private var isAdvertising = false
    
    // Connected clients
    private val connectedClients = mutableSetOf<String>()
    
    // Pending request data per client
    private val clientRequestData = mutableMapOf<String, ClientRequestData>()
    
    // OkHttp client for making actual HTTP requests
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(HTTP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(HTTP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(HTTP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()
    
    // GATT Service and Characteristics
    private lateinit var httpProxyService: BluetoothGattService
    private lateinit var uriCharacteristic: BluetoothGattCharacteristic
    private lateinit var headersCharacteristic: BluetoothGattCharacteristic
    private lateinit var statusCodeCharacteristic: BluetoothGattCharacteristic
    private lateinit var bodyCharacteristic: BluetoothGattCharacteristic
    private lateinit var controlPointCharacteristic: BluetoothGattCharacteristic
    private lateinit var httpsSecurityCharacteristic: BluetoothGattCharacteristic
    
    private data class ClientRequestData(
        var uri: String? = null,
        var headers: Map<String, String> = emptyMap(),
        var body: ByteArray? = null
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as ClientRequestData
            if (uri != other.uri) return false
            if (headers != other.headers) return false
            if (body != null) {
                if (other.body == null) return false
                if (!body.contentEquals(other.body)) return false
            } else if (other.body != null) return false
            return true
        }

        override fun hashCode(): Int {
            var result = uri?.hashCode() ?: 0
            result = 31 * result + headers.hashCode()
            result = 31 * result + (body?.contentHashCode() ?: 0)
            return result
        }
    }
    
    /**
     * Sets the callback for receiving server events.
     */
    fun setCallback(callback: HttpOverBleServerCallback) {
        this.callback = callback
    }
    
    /**
     * Checks if Bluetooth is enabled.
     */
    fun isBluetoothEnabled(): Boolean {
        return bluetoothAdapter?.isEnabled == true
    }
    
    /**
     * Starts the HTTP Proxy Service server.
     */
    fun start() {
        if (!isBluetoothEnabled()) {
            callback?.onError(HttpOverBleServerError.BluetoothNotEnabled("Bluetooth is not enabled"))
            return
        }
        
        if (isAdvertising) {
            Log.w(TAG, "Server is already running")
            return
        }
        
        setupGattService()
        startGattServer()
        startAdvertising()
    }
    
    /**
     * Stops the HTTP Proxy Service server.
     */
    fun stop() {
        stopAdvertising()
        stopGattServer()
        callback?.onServerStopped()
    }
    
    private fun setupGattService() {
        // Create the HTTP Proxy Service
        httpProxyService = BluetoothGattService(
            HttpProxyServiceConstants.HTTP_PROXY_SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )
        
        // URI Characteristic (write)
        uriCharacteristic = BluetoothGattCharacteristic(
            HttpProxyServiceConstants.URI_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        
        // HTTP Headers Characteristic (read/write)
        headersCharacteristic = BluetoothGattCharacteristic(
            HttpProxyServiceConstants.HTTP_HEADERS_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        
        // HTTP Status Code Characteristic (read/notify)
        statusCodeCharacteristic = BluetoothGattCharacteristic(
            HttpProxyServiceConstants.HTTP_STATUS_CODE_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        // Add Client Characteristic Configuration Descriptor for notifications
        val statusCodeDescriptor = BluetoothGattDescriptor(
            HttpProxyServiceConstants.CLIENT_CHARACTERISTIC_CONFIG_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        statusCodeCharacteristic.addDescriptor(statusCodeDescriptor)
        
        // HTTP Entity Body Characteristic (read/write)
        bodyCharacteristic = BluetoothGattCharacteristic(
            HttpProxyServiceConstants.HTTP_ENTITY_BODY_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        
        // HTTP Control Point Characteristic (write)
        controlPointCharacteristic = BluetoothGattCharacteristic(
            HttpProxyServiceConstants.HTTP_CONTROL_POINT_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        
        // HTTPS Security Characteristic (read)
        httpsSecurityCharacteristic = BluetoothGattCharacteristic(
            HttpProxyServiceConstants.HTTPS_SECURITY_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        
        // Add all characteristics to the service
        httpProxyService.addCharacteristic(uriCharacteristic)
        httpProxyService.addCharacteristic(headersCharacteristic)
        httpProxyService.addCharacteristic(statusCodeCharacteristic)
        httpProxyService.addCharacteristic(bodyCharacteristic)
        httpProxyService.addCharacteristic(controlPointCharacteristic)
        httpProxyService.addCharacteristic(httpsSecurityCharacteristic)
    }
    
    private fun startGattServer() {
        gattServer = bluetoothManager.openGattServer(context, gattServerCallback)
        gattServer?.addService(httpProxyService)
        Log.d(TAG, "GATT server started")
    }
    
    private fun stopGattServer() {
        gattServer?.close()
        gattServer = null
        connectedClients.clear()
        clientRequestData.clear()
        Log.d(TAG, "GATT server stopped")
    }
    
    private fun startAdvertising() {
        bluetoothLeAdvertiser = bluetoothAdapter?.bluetoothLeAdvertiser
        if (bluetoothLeAdvertiser == null) {
            callback?.onError(HttpOverBleServerError.AdvertisingFailed("BLE advertising not supported"))
            return
        }
        
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setConnectable(true)
            .setTimeout(0) // No timeout
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .build()
        
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .addServiceUuid(ParcelUuid(HttpProxyServiceConstants.HTTP_PROXY_SERVICE_UUID))
            .build()
        
        try {
            bluetoothLeAdvertiser?.startAdvertising(settings, data, advertiseCallback)
            Log.d(TAG, "Started advertising HTTP Proxy Service")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start advertising", e)
            callback?.onError(HttpOverBleServerError.AdvertisingFailed("Failed to start advertising: ${e.message}"))
        }
    }
    
    private fun stopAdvertising() {
        if (!isAdvertising) return
        
        try {
            bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
            isAdvertising = false
            Log.d(TAG, "Stopped advertising")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop advertising", e)
        }
    }
    
    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            isAdvertising = true
            callback?.onServerStarted()
            Log.d(TAG, "Advertising started successfully")
        }
        
        override fun onStartFailure(errorCode: Int) {
            isAdvertising = false
            val errorMessage = when (errorCode) {
                ADVERTISE_FAILED_DATA_TOO_LARGE -> "Data too large"
                ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "Too many advertisers"
                ADVERTISE_FAILED_ALREADY_STARTED -> "Already started"
                ADVERTISE_FAILED_INTERNAL_ERROR -> "Internal error"
                ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "Feature unsupported"
                else -> "Unknown error: $errorCode"
            }
            Log.e(TAG, "Advertising failed: $errorMessage")
            callback?.onError(HttpOverBleServerError.AdvertisingFailed(errorMessage))
        }
    }
    
    private val gattServerCallback = object : BluetoothGattServerCallback() {
        
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connectedClients.add(device.address)
                    clientRequestData[device.address] = ClientRequestData()
                    callback?.onClientConnected(device.address)
                    Log.d(TAG, "Client connected: ${device.address}")
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    connectedClients.remove(device.address)
                    clientRequestData.remove(device.address)
                    callback?.onClientDisconnected(device.address)
                    Log.d(TAG, "Client disconnected: ${device.address}")
                }
            }
        }
        
        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            val value = characteristic.value ?: byteArrayOf()
            val responseValue = if (offset < value.size) {
                Arrays.copyOfRange(value, offset, value.size)
            } else {
                byteArrayOf()
            }
            
            gattServer?.sendResponse(
                device,
                requestId,
                BluetoothGatt.GATT_SUCCESS,
                offset,
                responseValue
            )
        }
        
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            val clientData = clientRequestData[device.address] ?: ClientRequestData()
            
            when (characteristic.uuid) {
                HttpProxyServiceConstants.URI_CHARACTERISTIC_UUID -> {
                    clientData.uri = String(value, Charsets.UTF_8)
                    Log.d(TAG, "Received URI: ${clientData.uri}")
                }
                HttpProxyServiceConstants.HTTP_HEADERS_CHARACTERISTIC_UUID -> {
                    clientData.headers = HttpResponse.parseHeaders(value)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        characteristic.setValue(value)
                    } else {
                        @Suppress("DEPRECATION")
                        characteristic.value = value
                    }
                    Log.d(TAG, "Received headers: ${clientData.headers}")
                }
                HttpProxyServiceConstants.HTTP_ENTITY_BODY_CHARACTERISTIC_UUID -> {
                    clientData.body = value
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        characteristic.setValue(value)
                    } else {
                        @Suppress("DEPRECATION")
                        characteristic.value = value
                    }
                    Log.d(TAG, "Received body: ${value.size} bytes")
                }
                HttpProxyServiceConstants.HTTP_CONTROL_POINT_CHARACTERISTIC_UUID -> {
                    if (value.isNotEmpty()) {
                        handleControlPoint(device, value[0], clientData)
                    }
                }
            }
            
            clientRequestData[device.address] = clientData
            
            if (responseNeeded) {
                gattServer?.sendResponse(
                    device,
                    requestId,
                    BluetoothGatt.GATT_SUCCESS,
                    offset,
                    value
                )
            }
        }
        
        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (responseNeeded) {
                gattServer?.sendResponse(
                    device,
                    requestId,
                    BluetoothGatt.GATT_SUCCESS,
                    offset,
                    value
                )
            }
        }
        
        override fun onDescriptorReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            descriptor: BluetoothGattDescriptor
        ) {
            gattServer?.sendResponse(
                device,
                requestId,
                BluetoothGatt.GATT_SUCCESS,
                offset,
                descriptor.value ?: byteArrayOf()
            )
        }
    }
    
    private fun handleControlPoint(device: BluetoothDevice, opcode: Byte, clientData: ClientRequestData) {
        if (opcode == HttpProxyServiceConstants.OPCODE_HTTP_REQUEST_CANCEL) {
            Log.d(TAG, "Request cancelled by client")
            return
        }
        
        val uri = clientData.uri
        if (uri == null) {
            Log.e(TAG, "No URI set for request")
            return
        }
        
        val method = when (opcode) {
            HttpProxyServiceConstants.OPCODE_HTTP_GET_REQUEST,
            HttpProxyServiceConstants.OPCODE_HTTPS_GET_REQUEST -> HttpMethod.GET
            
            HttpProxyServiceConstants.OPCODE_HTTP_HEAD_REQUEST,
            HttpProxyServiceConstants.OPCODE_HTTPS_HEAD_REQUEST -> HttpMethod.HEAD
            
            HttpProxyServiceConstants.OPCODE_HTTP_POST_REQUEST,
            HttpProxyServiceConstants.OPCODE_HTTPS_POST_REQUEST -> HttpMethod.POST
            
            HttpProxyServiceConstants.OPCODE_HTTP_PUT_REQUEST,
            HttpProxyServiceConstants.OPCODE_HTTPS_PUT_REQUEST -> HttpMethod.PUT
            
            HttpProxyServiceConstants.OPCODE_HTTP_DELETE_REQUEST,
            HttpProxyServiceConstants.OPCODE_HTTPS_DELETE_REQUEST -> HttpMethod.DELETE
            
            else -> {
                Log.e(TAG, "Unknown opcode: $opcode")
                return
            }
        }
        
        val isHttps = opcode >= HttpProxyServiceConstants.OPCODE_HTTPS_GET_REQUEST
        
        val request = HttpRequest(
            uri = uri,
            method = method,
            headers = clientData.headers,
            body = clientData.body,
            isHttps = isHttps
        )
        
        callback?.onRequestReceived(request, device.address)
        
        // Execute HTTP request in background
        scope.launch {
            executeHttpRequest(device, request)
        }
    }
    
    private fun executeHttpRequest(device: BluetoothDevice, httpRequest: HttpRequest) {
        try {
            val requestBuilder = Request.Builder()
                .url(httpRequest.uri)
            
            // Add headers
            if (httpRequest.headers.isNotEmpty()) {
                requestBuilder.headers(httpRequest.headers.toHeaders())
            }
            
            // Set method and body
            val body = httpRequest.body?.let { 
                val contentType = httpRequest.headers["Content-Type"]?.toMediaTypeOrNull()
                it.toRequestBody(contentType) 
            }
            
            when (httpRequest.method) {
                HttpMethod.GET -> requestBuilder.get()
                HttpMethod.HEAD -> requestBuilder.head()
                HttpMethod.POST -> requestBuilder.post(body ?: "".toRequestBody(null))
                HttpMethod.PUT -> requestBuilder.put(body ?: "".toRequestBody(null))
                HttpMethod.DELETE -> if (body != null) requestBuilder.delete(body) else requestBuilder.delete()
            }
            
            val request = requestBuilder.build()
            Log.d(TAG, "Executing HTTP request: ${httpRequest.method} ${httpRequest.uri}")
            
            val response = httpClient.newCall(request).execute()
            
            val responseHeaders = mutableMapOf<String, String>()
            response.headers.forEach { (name, value) ->
                responseHeaders[name] = value
            }
            
            val responseBody = response.body?.bytes()
            
            val httpResponse = HttpResponse(
                statusCode = response.code,
                headers = responseHeaders,
                body = responseBody,
                isHttps = httpRequest.isHttps,
                certificateValidated = httpRequest.isHttps // Assume validated if request succeeded
            )
            
            sendResponse(device, httpResponse)
            callback?.onResponseSent(httpResponse, device.address)
            
            Log.d(TAG, "HTTP response: ${response.code}")
            
        } catch (e: Exception) {
            Log.e(TAG, "HTTP request failed", e)
            callback?.onError(HttpOverBleServerError.HttpRequestFailed("HTTP request failed: ${e.message}"))
            
            // Send error response
            val errorResponse = HttpResponse(
                statusCode = 500,
                headers = emptyMap(),
                body = "Error: ${e.message}".toByteArray(),
                isHttps = httpRequest.isHttps,
                certificateValidated = false
            )
            sendResponse(device, errorResponse)
        }
    }
    
    private fun sendResponse(device: BluetoothDevice, response: HttpResponse) {
        try {
            // Update characteristic values
            val statusCodeBytes = HttpResponse.serializeStatusCode(response.statusCode)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                statusCodeCharacteristic.setValue(statusCodeBytes)
            } else {
                @Suppress("DEPRECATION")
                statusCodeCharacteristic.value = statusCodeBytes
            }
            
            val headerBytes = response.headers.entries
                .joinToString("\r\n") { "${it.key}: ${it.value}" }
                .toByteArray(Charsets.UTF_8)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                headersCharacteristic.setValue(headerBytes)
            } else {
                @Suppress("DEPRECATION")
                headersCharacteristic.value = headerBytes
            }
            
            response.body?.let { body ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    bodyCharacteristic.setValue(body)
                } else {
                    @Suppress("DEPRECATION")
                    bodyCharacteristic.value = body
                }
            }
            
            if (response.isHttps) {
                val securityValue = if (response.certificateValidated) {
                    HttpProxyServiceConstants.HTTPS_SECURITY_CERTIFICATE_VALIDATED
                } else {
                    HttpProxyServiceConstants.HTTPS_SECURITY_CERTIFICATE_NOT_VALIDATED
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    httpsSecurityCharacteristic.setValue(byteArrayOf(securityValue))
                } else {
                    @Suppress("DEPRECATION")
                    httpsSecurityCharacteristic.value = byteArrayOf(securityValue)
                }
            }
            
            // Notify client about the status code (which triggers response reading)
            gattServer?.notifyCharacteristicChanged(device, statusCodeCharacteristic, false)
            
            Log.d(TAG, "Response sent to client: ${response.statusCode}")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send response", e)
            callback?.onError(HttpOverBleServerError.ResponseSendFailed("Failed to send response: ${e.message}"))
        }
    }
    
    /**
     * Releases all resources held by this server.
     */
    fun close() {
        stop()
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
    }
}
