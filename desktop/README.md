# HTTP over Bluetooth - Desktop Server

A macOS desktop application that implements the HTTP Proxy Service (HPS) 1.0 specification as a BLE Peripheral. This allows Android and iOS clients to send HTTP requests through this server via Bluetooth.

## Overview

This desktop application consists of two parts:
- **Backend Server (Node.js)**: Implements the BLE Peripheral using `@abandonware/bleno` and provides the HTTP Proxy Service
- **Frontend (Vue.js)**: Provides a user interface to start/stop the HPS service, view logs, and test HTTP requests

## Features

### User Interface
- **Server Status Dashboard**: Real-time display of server state, Bluetooth status, advertising state, and connected clients
- **One-Click Controls**: Easy start/stop buttons for the HPS service
- **Live Log Viewer**: Color-coded, real-time logs showing all BLE and HTTP activity
- **HTTP Test Form**: Built-in form to test HTTP requests directly from the UI
- **Responsive Design**: Modern, gradient-themed interface that works on different screen sizes

### Backend Capabilities
- **Full HPS 1.0 Implementation**: Complete Bluetooth HTTP Proxy Service specification support
- **All HTTP Methods**: GET, POST, PUT, DELETE, HEAD support for both HTTP and HTTPS
- **Automatic Request Execution**: Receives BLE requests and executes actual HTTP/HTTPS calls
- **Real-time Notifications**: BLE characteristic notifications for status updates
- **Multi-Client Support**: Handle multiple connected BLE clients simultaneously
- **Robust Error Handling**: Graceful error handling and reporting

## Architecture

```
┌─────────────────┐         BLE          ┌─────────────────┐       WiFi       ┌─────────────────┐
│  Mobile Client  │ ◄──────────────────► │  Desktop Server │ ◄──────────────►│  HTTP Server    │
│   (Android/iOS) │   HTTP Proxy Service │     (macOS)     │   Real HTTP Req │  (api.example)  │
└─────────────────┘                       └─────────────────┘                  └─────────────────┘
```

## Requirements

- macOS 10.14 or later (with Bluetooth LE support)
- Node.js 16 or later
- npm or yarn

## Installation

1. Navigate to the desktop directory:
```bash
cd desktop
```

2. Install dependencies:
```bash
npm install
```

## Usage

### Running the Application

1. Start both the backend server and frontend:
```bash
npm run dev
```

This will:
- Start the backend server on `http://localhost:3000`
- Start the frontend development server on `http://localhost:5173`
- Open your browser automatically to the frontend

2. Alternatively, you can run them separately:
```bash
# Terminal 1: Start backend server
npm run server

# Terminal 2: Start frontend
npm run frontend
```

### Using the Application

1. **Start the HPS Server**:
   - Click the "Start HPS Server" button in the UI
   - The server will begin advertising the HTTP Proxy Service via BLE
   - Mobile clients can now discover and connect to "HPS Server"

2. **Connect from Mobile Device**:
   - Use the Android or iOS HTTP over Bluetooth client app
   - Scan for BLE devices
   - Connect to "HPS Server"
   - Send HTTP requests through BLE

3. **Monitor Activity**:
   - View server status (Running/Stopped, Bluetooth state, Connected clients)
   - Monitor real-time logs showing:
     - BLE connection events
     - Received HTTP requests
     - Executed HTTP responses
     - Any errors

4. **Test HTTP Requests**:
   - Use the built-in test form to send HTTP requests directly
   - Verify that HTTP execution is working correctly
   - Test different methods (GET, POST, PUT, DELETE, HEAD)
   - View response status and body

## HTTP Proxy Service Implementation

This server implements the Bluetooth HTTP Proxy Service 1.0 specification with the following characteristics:

| Characteristic | UUID | Properties | Description |
|----------------|------|------------|-------------|
| URI | 0x2AB6 | Write | Target HTTP/HTTPS URI |
| HTTP Headers | 0x2AB7 | Read, Write | Request/Response headers |
| HTTP Status Code | 0x2AB8 | Read, Notify | Response status code |
| HTTP Entity Body | 0x2AB9 | Read, Write | Request/Response body |
| HTTP Control Point | 0x2ABA | Write | Execute HTTP request |
| HTTPS Security | 0x2ABB | Read | Certificate validation status |

### Supported HTTP Methods

- GET
- POST
- PUT
- DELETE
- HEAD

Both HTTP and HTTPS requests are supported.

## API Endpoints

The backend server provides the following REST API endpoints:

- `GET /api/status` - Get server status
- `GET /api/logs` - Get server logs
- `POST /api/logs/clear` - Clear logs
- `POST /api/start` - Start HPS service
- `POST /api/stop` - Stop HPS service
- `POST /api/test-request` - Send a test HTTP request

## Project Structure

```
desktop/
├── server/                 # Backend Node.js server
│   ├── index.js           # Main server file
│   ├── hps-service.js     # HTTP Proxy Service implementation
│   └── hps-constants.js   # HPS constants and UUIDs
├── frontend/              # Vue.js frontend
│   ├── src/
│   │   ├── App.vue        # Main application component
│   │   ├── main.js        # Vue app initialization
│   │   └── style.css      # Styles
│   └── index.html         # HTML entry point
├── vite.config.js         # Vite configuration
├── package.json           # Dependencies and scripts
└── README.md             # This file
```

## Troubleshooting

### Bluetooth Not Available

If you see "Bluetooth not powered on" or similar errors:
1. Make sure Bluetooth is enabled in macOS System Preferences
2. Grant Bluetooth permissions to Terminal or your Node.js process
3. Try restarting the application

### Permission Issues on macOS

On macOS, you may need to grant Bluetooth permissions:
1. Go to System Preferences → Security & Privacy → Bluetooth
2. Add Terminal (or your IDE) to the allowed apps list

### Port Already in Use

If port 3000 or 5173 is already in use:
- Change the port in `server/index.js` (line 7)
- Change the port in `vite.config.js` (line 8)

## Development

### Building for Production

```bash
npm run build
```

This creates an optimized production build in the `frontend/dist` directory.

### Preview Production Build

```bash
npm run preview
```

## Testing with Mobile Clients

1. Start the desktop server as described above
2. On your Android/iOS device:
   - Install the HTTP over Bluetooth client app
   - Enable Bluetooth
   - Scan for BLE devices
   - Connect to "HPS Server"
   - Send HTTP requests
3. Monitor the desktop server logs to see received requests

## License

MIT

## References

- [Bluetooth HTTP Proxy Service 1.0 Specification](https://www.bluetooth.com/specifications/specs/http-proxy-service-1-0/)
- [bleno - BLE Peripheral library](https://github.com/abandonware/bleno)
- [Vue.js Documentation](https://vuejs.org/)
