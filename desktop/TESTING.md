# Testing Guide for HTTP over Bluetooth Desktop Server

This document describes how to test the HTTP over Bluetooth desktop server on macOS.

## Prerequisites

- macOS 10.14 or later with Bluetooth LE hardware
- Node.js 16+ installed
- Android or iOS device with the HTTP over Bluetooth client app
- Bluetooth enabled on both devices

## Installation

1. Clone the repository and navigate to the desktop directory:
```bash
cd desktop
npm install
```

## Testing Steps

### 1. Test Server Startup

**Expected Behavior**: The server should start without errors and the Express API should be accessible.

```bash
npm run server
```

**Verify**:
- Console shows: "HTTP over Bluetooth Desktop Server running on http://localhost:3000"
- No errors related to Express or API endpoints
- Bluetooth state is logged (should be "poweredOn" if Bluetooth is enabled)

**Note**: If you see "EAFNOSUPPORT, Address family not supported by protocol", this indicates the environment doesn't have Bluetooth hardware support. This is expected in virtualized or containerized environments.

### 2. Test Frontend

**Expected Behavior**: The Vue.js frontend should start and be accessible in a browser.

```bash
npm run frontend
```

**Verify**:
- Browser opens automatically to http://localhost:5173
- UI displays correctly with all sections visible:
  - Server Status cards
  - Control buttons (Start/Stop HPS Server, Clear Logs)
  - Test Request form
  - Server Logs section
- No console errors in browser developer tools

### 3. Test Full Application

**Expected Behavior**: Both frontend and backend should run concurrently.

```bash
npm run dev
```

**Verify**:
- Both servers start successfully
- Frontend can communicate with backend API
- Status section shows current server state

### 4. Test API Endpoints

With the server running, test the API endpoints:

```bash
# Get server status
curl http://localhost:3000/api/status

# Expected response:
# {
#   "isServerStarted": false,
#   "isAdvertising": false,
#   "bluetoothState": "poweredOn" or other state,
#   "connectedClients": 0,
#   "clients": []
# }

# Get logs
curl http://localhost:3000/api/logs

# Expected: Array of log objects
```

### 5. Test BLE Advertising (macOS only)

**Prerequisites**: macOS with Bluetooth hardware

1. Start the application:
```bash
npm run dev
```

2. In the browser UI, click "Start HPS Server"

**Verify**:
- Status changes to "Running"
- Bluetooth status shows "poweredOn"
- Advertising status shows "Active"
- Logs show:
  - "Bluetooth powered on, ready to start advertising"
  - "Started advertising HTTP Proxy Service"
  - "HTTP Proxy Service registered successfully"

3. On an Android or iOS device:
   - Open Bluetooth settings or a BLE scanner app
   - Look for a device named "HPS Server"
   - The device should advertise service UUID "1823"

### 6. Test BLE Connection and HTTP Request Execution

**Prerequisites**: Mobile device with HTTP over Bluetooth client app

1. Ensure the desktop server is started and advertising
2. On the mobile device:
   - Scan for BLE devices
   - Connect to "HPS Server"
3. Send a test HTTP request from the mobile app (e.g., GET https://httpbin.org/get)

**Verify on Desktop Server**:
- Logs show:
  - "Client connected: [address]"
  - "Request received: GET https://httpbin.org/get"
  - "Response received: 200"
  - "Response sent: 200"
- Connected Clients count increases to 1

**Verify on Mobile Device**:
- Request completes successfully
- Response status code is displayed
- Response body is received

### 7. Test HTTP Execution Functionality

Use the built-in test request form in the UI:

1. In the browser, fill in the test request form:
   - Method: GET
   - URL: https://httpbin.org/get
   - Headers: `{}`
   - Click "Send Test Request"

**Verify**:
- Request completes without errors
- Response section appears with:
  - Status code (should be 200)
  - Response headers
  - Response body (JSON data from httpbin)
- Logs show:
  - "Test request: GET https://httpbin.org/get"
  - "Test request completed: 200"

2. Test POST request:
   - Method: POST
   - URL: https://httpbin.org/post
   - Headers: `{"Content-Type": "application/json"}`
   - Body: `{"test": "data"}`
   - Click "Send Test Request"

**Verify**:
- Request completes successfully
- Response shows the posted data echoed back

### 8. Test Error Handling

1. Test with invalid URL:
   - URL: https://invalid-domain-that-does-not-exist.com
   - Click "Send Test Request"
   
**Verify**:
- Error is logged
- Response shows error message
- Server doesn't crash

2. Test with malformed JSON in headers:
   - Headers: `{invalid json}`
   - Click "Send Test Request"
   
**Verify**:
- Error alert appears
- Server remains stable

### 9. Test Multiple Clients

**Prerequisites**: Multiple mobile devices with HTTP over Bluetooth client apps

1. Connect multiple mobile devices to the "HPS Server"
2. Send requests from different devices

**Verify**:
- Connected Clients count reflects the number of connected devices
- Requests from all clients are handled
- Logs show activity from different client addresses
- Server handles concurrent requests

### 10. Test Server Stop/Restart

1. Click "Stop HPS Server" button

**Verify**:
- Status changes to "Stopped"
- Advertising status shows "Inactive"
- Connected clients are disconnected
- Logs show: "HTTP Proxy Server stopped"

2. Click "Start HPS Server" again

**Verify**:
- Server restarts successfully
- Can accept new connections

## Known Limitations and Expected Behaviors

### Environment without Bluetooth Hardware

If you see this error:
```
Error: EAFNOSUPPORT, Address family not supported by protocol
```

This is expected in environments without Bluetooth hardware (e.g., Docker containers, VMs without USB passthrough, GitHub Actions runners). The application is designed for macOS with native Bluetooth support.

### macOS Permissions

On first run, macOS may ask for Bluetooth permissions for Terminal or Node.js. You must grant these permissions for BLE functionality to work.

### BLE Range Limitations

Bluetooth LE has a limited range (typically 10-30 meters). Ensure mobile devices are within range of the macOS machine running the server.

### MTU Limitations

BLE has MTU (Maximum Transmission Unit) limitations that may affect large HTTP requests or responses. The implementation chunks data appropriately, but very large payloads may take time to transfer.

## Troubleshooting

### Server won't start
- Check that ports 3000 and 5173 are not in use
- Verify Node.js version is 16 or later
- Try `npm install` again to ensure all dependencies are installed

### Bluetooth issues
- Ensure Bluetooth is enabled in macOS System Preferences
- Check that no other apps are using BLE peripheral mode
- Restart Bluetooth: Turn off, wait 5 seconds, turn on
- Restart the application

### Mobile devices can't find server
- Verify the server shows "Advertising: Active"
- Check that Bluetooth is enabled on the mobile device
- Try moving devices closer together
- Check macOS Bluetooth permissions

### Requests fail or timeout
- Verify the macOS machine has internet connectivity
- Check that URLs are accessible from the macOS machine
- Look for errors in the server logs
- Try a simpler request (e.g., GET http://httpbin.org/get)

## Automated Testing

Due to the hardware dependencies of BLE, automated testing is limited. However, you can test:

1. **Syntax validation**:
```bash
node --check server/index.js
node --check server/hps-service.js
node --check server/hps-constants.js
```

2. **Module imports**:
```bash
node -e "import('./server/hps-constants.js').then(() => console.log('OK'))"
```

3. **Frontend build**:
```bash
npm run build
```

4. **API endpoints (without BLE)**:
```bash
# Start server (will fail on BLE init but Express API should work)
# Then test endpoints with curl
curl http://localhost:3000/api/status
curl http://localhost:3000/api/logs
```

## Security Testing

The implementation has been scanned with CodeQL and npm audit. No vulnerabilities were found in the dependencies at the time of implementation.

To verify:
```bash
npm audit
```

## Performance Testing

For production use, consider testing:
- Maximum number of concurrent client connections
- Large HTTP response handling
- Long-running server stability
- Memory usage over time
- Request throughput

## Contributing

If you find issues during testing, please report them with:
- macOS version
- Node.js version
- Bluetooth hardware information
- Detailed steps to reproduce
- Error logs and screenshots
