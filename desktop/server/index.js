import express from 'express';
import cors from 'cors';
import bleno from '@abandonware/bleno';
import { HttpProxyService } from './hps-service.js';

const app = express();
const PORT = 3000;

// Middleware
app.use(cors());
app.use(express.json());

// State
let hpsService = null;
let isAdvertising = false;
let isServerStarted = false;
let logs = [];
let connectedClients = [];

// Logging function
function addLog(message, type = 'info') {
    const log = {
        timestamp: new Date().toISOString(),
        message,
        type
    };
    logs.push(log);
    console.log(`[${type.toUpperCase()}] ${message}`);
    
    // Keep only last 100 logs
    if (logs.length > 100) {
        logs = logs.slice(-100);
    }
}

// Initialize bleno
bleno.on('stateChange', (state) => {
    addLog(`Bluetooth state changed to: ${state}`, 'info');
    
    if (state === 'poweredOn') {
        addLog('Bluetooth powered on, ready to start advertising', 'success');
    } else {
        addLog(`Bluetooth not available: ${state}`, 'warning');
        if (isAdvertising) {
            bleno.stopAdvertising();
            isAdvertising = false;
        }
    }
});

bleno.on('advertisingStart', (error) => {
    if (error) {
        addLog(`Error starting advertising: ${error.message}`, 'error');
        isAdvertising = false;
    } else {
        addLog('Started advertising HTTP Proxy Service', 'success');
        isAdvertising = true;
        
        if (hpsService) {
            bleno.setServices([hpsService.getService()], (error) => {
                if (error) {
                    addLog(`Error setting services: ${error.message}`, 'error');
                } else {
                    addLog('HTTP Proxy Service registered successfully', 'success');
                }
            });
        }
    }
});

bleno.on('advertisingStop', () => {
    addLog('Stopped advertising', 'info');
    isAdvertising = false;
});

bleno.on('accept', (clientAddress) => {
    addLog(`Client connected: ${clientAddress}`, 'success');
    connectedClients.push({
        address: clientAddress,
        connectedAt: new Date().toISOString()
    });
});

bleno.on('disconnect', (clientAddress) => {
    addLog(`Client disconnected: ${clientAddress}`, 'info');
    connectedClients = connectedClients.filter(c => c.address !== clientAddress);
});

// API endpoints

// Get server status
app.get('/api/status', (req, res) => {
    res.json({
        isServerStarted,
        isAdvertising,
        bluetoothState: bleno.state,
        connectedClients: connectedClients.length,
        clients: connectedClients
    });
});

// Get logs
app.get('/api/logs', (req, res) => {
    res.json(logs);
});

// Clear logs
app.post('/api/logs/clear', (req, res) => {
    logs = [];
    addLog('Logs cleared', 'info');
    res.json({ success: true });
});

// Start HPS service
app.post('/api/start', (req, res) => {
    try {
        if (isServerStarted) {
            return res.status(400).json({ error: 'Server already started' });
        }
        
        if (bleno.state !== 'poweredOn') {
            return res.status(400).json({ 
                error: 'Bluetooth not powered on',
                state: bleno.state
            });
        }
        
        // Create HPS service
        hpsService = new HttpProxyService();
        
        // Set up event handlers
        hpsService.onRequestReceived = (request) => {
            addLog(`Request received: ${request.method} ${request.uri}`, 'info');
        };
        
        hpsService.onResponseSent = (response) => {
            addLog(`Response sent: ${response.statusCode}`, 'success');
        };
        
        hpsService.onError = (error) => {
            addLog(`Error: ${error.message}`, 'error');
        };
        
        // Start advertising
        bleno.startAdvertising('HPS Server', ['1823'], (error) => {
            if (error) {
                addLog(`Error starting advertising: ${error.message}`, 'error');
                return res.status(500).json({ error: error.message });
            }
        });
        
        isServerStarted = true;
        addLog('HTTP Proxy Server started', 'success');
        
        res.json({ 
            success: true,
            message: 'HPS Server started successfully'
        });
    } catch (error) {
        addLog(`Error starting server: ${error.message}`, 'error');
        res.status(500).json({ error: error.message });
    }
});

// Stop HPS service
app.post('/api/stop', (req, res) => {
    try {
        if (!isServerStarted) {
            return res.status(400).json({ error: 'Server not started' });
        }
        
        if (isAdvertising) {
            bleno.stopAdvertising();
        }
        
        bleno.disconnect();
        
        isServerStarted = false;
        hpsService = null;
        connectedClients = [];
        
        addLog('HTTP Proxy Server stopped', 'info');
        
        res.json({ 
            success: true,
            message: 'HPS Server stopped successfully'
        });
    } catch (error) {
        addLog(`Error stopping server: ${error.message}`, 'error');
        res.status(500).json({ error: error.message });
    }
});

// Test HTTP request endpoint (for frontend testing)
app.post('/api/test-request', async (req, res) => {
    try {
        const { url, method = 'GET', headers = {}, body = null } = req.body;
        
        if (!url) {
            return res.status(400).json({ error: 'URL is required' });
        }
        
        addLog(`Test request: ${method} ${url}`, 'info');
        
        const axios = (await import('axios')).default;
        const response = await axios({
            method: method.toLowerCase(),
            url,
            headers,
            data: body,
            validateStatus: () => true,
            timeout: 30000
        });
        
        addLog(`Test request completed: ${response.status}`, 'success');
        
        res.json({
            statusCode: response.status,
            headers: response.headers,
            body: response.data
        });
    } catch (error) {
        addLog(`Test request error: ${error.message}`, 'error');
        res.status(500).json({ error: error.message });
    }
});

// Start Express server
app.listen(PORT, () => {
    console.log(`HTTP over Bluetooth Desktop Server running on http://localhost:${PORT}`);
    addLog(`Server API listening on port ${PORT}`, 'success');
});

// Cleanup on exit
process.on('SIGINT', () => {
    console.log('\nShutting down...');
    if (isAdvertising) {
        bleno.stopAdvertising();
    }
    bleno.disconnect();
    process.exit();
});
