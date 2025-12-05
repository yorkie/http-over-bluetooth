# HTTP Over Bluetooth SDK

Send HTTP requests over Bluetooth Low Energy (BLE) using the HTTP Proxy Service (HPS) 1.0 specification.

Reference: [Bluetooth HTTP Proxy Service 1.0](https://www.bluetooth.com/specifications/specs/http-proxy-service-1-0/)

## Overview

This repository provides Android, iOS, and Desktop implementations that enable:

- **Client Mode**: Send HTTP/HTTPS requests via BLE to connected BLE devices
- **Server Mode**: Receive BLE data from clients, execute actual HTTP/HTTPS requests over WiFi or 5G, and return responses
- **Desktop Server**: macOS application with Vue.js frontend for testing and development

## Architecture

```
┌─────────────────┐         BLE          ┌─────────────────┐       WiFi/5G       ┌─────────────────┐
│   BLE Client    │ ◄──────────────────► │   BLE Server    │ ◄──────────────────►│  HTTP Server    │
│  (No Internet)  │   HTTP Proxy Service │  (Has Internet) │   Real HTTP Request │  (api.example)  │
└─────────────────┘                       └─────────────────┘                      └─────────────────┘
```

## Android SDK

### Requirements

- Android API 23+ (Android 6.0 Marshmallow)
- Bluetooth LE support
- Required permissions: Bluetooth, Location (for BLE scanning), Internet

### Installation

Add to your `build.gradle`:

```gradle
dependencies {
    implementation project(':http-over-bluetooth')
}
```

### Sample App

A sample Android app is included in the `android/sample` directory. The sample demonstrates:
- Running as a BLE server that receives HTTP requests from clients and executes them
- Running as a BLE client that scans for and connects to BLE servers, then sends HTTP requests

To run the sample:
1. Open the `android` folder in Android Studio
2. Select the `sample` configuration
3. Run on a device with Bluetooth LE support

### Client Usage

```kotlin
import com.rokid.mlabs.httpoverble.client.HttpOverBleClient
import com.rokid.mlabs.httpoverble.client.HttpOverBleClientCallback
import com.rokid.mlabs.httpoverble.client.HttpOverBleError
import com.rokid.mlabs.httpoverble.common.HttpMethod
import com.rokid.mlabs.httpoverble.common.HttpRequest
import com.rokid.mlabs.httpoverble.common.HttpResponse

class MainActivity : AppCompatActivity(), HttpOverBleClientCallback {
    
    private lateinit var client: HttpOverBleClient
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        client = HttpOverBleClient(this)
        client.setCallback(this)
        
        // Start scanning for HTTP Proxy servers
        client.startScanning()
    }
    
    override fun onServerFound(deviceAddress: String, deviceName: String?) {
        // Found a BLE server, connect to it
        client.connect(deviceAddress)
    }
    
    override fun onConnected(deviceAddress: String) {
        // Connected! Send an HTTP request
        val request = HttpRequest(
            uri = "https://api.example.com/data",
            method = HttpMethod.GET,
            headers = mapOf("Accept" to "application/json"),
            isHttps = true
        )
        client.sendRequest(request)
    }
    
    override fun onResponseReceived(response: HttpResponse) {
        // Handle the HTTP response
        println("Status: ${response.statusCode}")
        println("Body: ${String(response.body ?: byteArrayOf())}")
    }
    
    override fun onDisconnected(deviceAddress: String) {
        // Handle disconnection
    }
    
    override fun onError(error: HttpOverBleError) {
        // Handle errors
        println("Error: ${error.message}")
    }
    
    override fun onDestroy() {
        super.onDestroy()
        client.close()
    }
}
```

### Server Usage

```kotlin
import com.rokid.mlabs.httpoverble.server.HttpOverBleServer
import com.rokid.mlabs.httpoverble.server.HttpOverBleServerCallback
import com.rokid.mlabs.httpoverble.server.HttpOverBleServerError
import com.rokid.mlabs.httpoverble.common.HttpRequest
import com.rokid.mlabs.httpoverble.common.HttpResponse

class ServerActivity : AppCompatActivity(), HttpOverBleServerCallback {
    
    private lateinit var server: HttpOverBleServer
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        server = HttpOverBleServer(this)
        server.setCallback(this)
        
        // Start the HTTP Proxy server
        server.start()
    }
    
    override fun onServerStarted() {
        println("Server started, advertising HTTP Proxy Service")
    }
    
    override fun onClientConnected(deviceAddress: String) {
        println("Client connected: $deviceAddress")
    }
    
    override fun onRequestReceived(request: HttpRequest, clientAddress: String) {
        println("Received request: ${request.method} ${request.uri}")
        // The server automatically executes the HTTP request and sends the response
    }
    
    override fun onResponseSent(response: HttpResponse, clientAddress: String) {
        println("Response sent: ${response.statusCode}")
    }
    
    override fun onClientDisconnected(deviceAddress: String) {
        println("Client disconnected: $deviceAddress")
    }
    
    override fun onServerStopped() {
        println("Server stopped")
    }
    
    override fun onError(error: HttpOverBleServerError) {
        println("Error: ${error.message}")
    }
    
    override fun onDestroy() {
        super.onDestroy()
        server.close()
    }
}
```

### Required Permissions (AndroidManifest.xml)

```xml
<!-- Bluetooth permissions -->
<uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.BLUETOOTH_ADVERTISE" />

<!-- Location for BLE scanning -->
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />

<!-- Internet for HTTP requests -->
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />

<uses-feature android:name="android.hardware.bluetooth_le" android:required="true" />
```

## iOS SDK

### Requirements

- iOS 13.0+
- Bluetooth LE support
- Required capabilities: Bluetooth (Central and Peripheral), Background Modes (if needed)

### Installation

#### Swift Package Manager

Add to your `Package.swift`:

```swift
dependencies: [
    .package(path: "../ios/HTTPOverBluetooth")
]
```

Or add via Xcode: File → Add Packages → Add Local Package

### Client Usage

```swift
import HTTPOverBluetooth

class ViewController: UIViewController, HTTPOverBLEClientDelegate {
    
    private var client: HTTPOverBLEClient!
    
    override func viewDidLoad() {
        super.viewDidLoad()
        
        client = HTTPOverBLEClient()
        client.delegate = self
        
        // Start scanning for HTTP Proxy servers
        client.startScanning()
    }
    
    // MARK: - HTTPOverBLEClientDelegate
    
    func httpOverBLEClient(didFindServer deviceIdentifier: UUID, name deviceName: String?) {
        // Found a BLE server, connect to it
        client.connect(to: deviceIdentifier)
    }
    
    func httpOverBLEClientDidConnect(deviceIdentifier: UUID) {
        // Connected! Send an HTTP request
        let request = HTTPRequest(
            uri: "https://api.example.com/data",
            method: .get,
            headers: ["Accept": "application/json"],
            isHTTPS: true
        )
        client.sendRequest(request)
    }
    
    func httpOverBLEClient(didReceiveResponse response: HTTPResponse) {
        // Handle the HTTP response
        print("Status: \(response.statusCode)")
        if let body = response.body, let bodyString = String(data: body, encoding: .utf8) {
            print("Body: \(bodyString)")
        }
    }
    
    func httpOverBLEClientDidDisconnect(deviceIdentifier: UUID) {
        // Handle disconnection
    }
    
    func httpOverBLEClient(didEncounterError error: HTTPOverBLEClientError) {
        // Handle errors
        print("Error: \(error)")
    }
    
    deinit {
        client.close()
    }
}
```

### Server Usage

```swift
import HTTPOverBluetooth

class ServerViewController: UIViewController, HTTPOverBLEServerDelegate {
    
    private var server: HTTPOverBLEServer!
    
    override func viewDidLoad() {
        super.viewDidLoad()
        
        server = HTTPOverBLEServer()
        server.delegate = self
        
        // Start the HTTP Proxy server
        server.start()
    }
    
    // MARK: - HTTPOverBLEServerDelegate
    
    func httpOverBLEServerDidStart() {
        print("Server started, advertising HTTP Proxy Service")
    }
    
    func httpOverBLEServer(didConnectClient deviceIdentifier: UUID) {
        print("Client connected: \(deviceIdentifier)")
    }
    
    func httpOverBLEServer(didReceiveRequest request: HTTPRequest, from clientIdentifier: UUID) {
        print("Received request: \(request.method.rawValue) \(request.uri)")
        // The server automatically executes the HTTP request and sends the response
    }
    
    func httpOverBLEServer(didSendResponse response: HTTPResponse, to clientIdentifier: UUID) {
        print("Response sent: \(response.statusCode)")
    }
    
    func httpOverBLEServer(didDisconnectClient deviceIdentifier: UUID) {
        print("Client disconnected: \(deviceIdentifier)")
    }
    
    func httpOverBLEServerDidStop() {
        print("Server stopped")
    }
    
    func httpOverBLEServer(didEncounterError error: HTTPOverBLEServerError) {
        print("Error: \(error)")
    }
    
    deinit {
        server.close()
    }
}
```

### Required Info.plist Keys

```xml
<key>NSBluetoothAlwaysUsageDescription</key>
<string>This app uses Bluetooth to send HTTP requests.</string>
<key>NSBluetoothPeripheralUsageDescription</key>
<string>This app uses Bluetooth to receive HTTP requests.</string>
```

### Required Capabilities

Enable in Xcode under Signing & Capabilities:
- Background Modes → Uses Bluetooth LE accessories (for client)
- Background Modes → Acts as a Bluetooth LE accessory (for server)

## Desktop Server (macOS)

### Requirements

- macOS 10.14 or later
- Node.js 16 or later
- Bluetooth LE support

### Installation

```bash
cd desktop
npm install
```

### Usage

Start the desktop server with Vue.js frontend:

```bash
npm run dev
```

This will:
- Start the backend server on `http://localhost:3000`
- Start the frontend on `http://localhost:5173`
- Open your browser to the UI

The desktop application provides:
- **HTTP Proxy Service (BLE Peripheral)**: Acts as a BLE server that mobile clients can connect to
- **Web UI**: Control panel to start/stop the service, view logs, and test requests
- **Real-time Monitoring**: View connected clients and request/response activity

For more details, see [desktop/README.md](desktop/README.md).

## HTTP Proxy Service (HPS) Specification

This SDK implements the Bluetooth HTTP Proxy Service 1.0 specification:

### Service UUID
- HTTP Proxy Service: `0x1823`

### Characteristic UUIDs
| Characteristic | UUID | Properties |
|----------------|------|------------|
| URI | `0x2AB6` | Write |
| HTTP Headers | `0x2AB7` | Read, Write |
| HTTP Status Code | `0x2AB8` | Read, Notify |
| HTTP Entity Body | `0x2AB9` | Read, Write |
| HTTP Control Point | `0x2ABA` | Write |
| HTTPS Security | `0x2ABB` | Read |

### Control Point Opcodes
| Opcode | Value | Description |
|--------|-------|-------------|
| HTTP GET | 0x01 | Send HTTP GET request |
| HTTP HEAD | 0x02 | Send HTTP HEAD request |
| HTTP POST | 0x03 | Send HTTP POST request |
| HTTP PUT | 0x04 | Send HTTP PUT request |
| HTTP DELETE | 0x05 | Send HTTP DELETE request |
| HTTPS GET | 0x06 | Send HTTPS GET request |
| HTTPS HEAD | 0x07 | Send HTTPS HEAD request |
| HTTPS POST | 0x08 | Send HTTPS POST request |
| HTTPS PUT | 0x09 | Send HTTPS PUT request |
| HTTPS DELETE | 0x0A | Send HTTPS DELETE request |
| Cancel Request | 0x0B | Cancel current request |

## License

MIT License
