<template>
  <div class="container">
    <h1>🔵 HTTP over Bluetooth</h1>
    <p class="subtitle">Desktop Server for macOS</p>

    <div class="info-box">
      <p><strong>Instructions:</strong></p>
      <p>1. Click "Start HPS Server" to begin advertising the HTTP Proxy Service via BLE</p>
      <p>2. Connect from your Android or iOS device using the HTTP over Bluetooth client</p>
      <p>3. Send HTTP requests from your mobile device - they will be executed by this server</p>
      <p>4. Use the test request form below to verify HTTP execution is working</p>
    </div>

    <!-- Status Section -->
    <div class="status-section">
      <div class="status-card">
        <h3>Server Status</h3>
        <div class="status-value">
          <span 
            class="status-badge" 
            :class="status.isServerStarted ? 'success' : 'error'"
          >
            {{ status.isServerStarted ? '🟢 Running' : '⚫ Stopped' }}
          </span>
        </div>
      </div>

      <div class="status-card">
        <h3>Bluetooth</h3>
        <div class="status-value">
          <span 
            class="status-badge" 
            :class="getBluetoothStatusClass()"
          >
            {{ status.bluetoothState || 'Unknown' }}
          </span>
        </div>
      </div>

      <div class="status-card">
        <h3>Advertising</h3>
        <div class="status-value">
          <span 
            class="status-badge" 
            :class="status.isAdvertising ? 'success' : 'info'"
          >
            {{ status.isAdvertising ? '📡 Active' : '⏸️ Inactive' }}
          </span>
        </div>
      </div>

      <div class="status-card">
        <h3>Connected Clients</h3>
        <div class="status-value">
          {{ status.connectedClients || 0 }}
        </div>
      </div>
    </div>

    <!-- Control Section -->
    <div class="control-section">
      <button 
        class="btn-primary" 
        @click="startServer" 
        :disabled="status.isServerStarted || isLoading"
      >
        <span v-if="isLoading" class="loading"></span>
        <span v-else>▶️</span>
        Start HPS Server
      </button>

      <button 
        class="btn-danger" 
        @click="stopServer" 
        :disabled="!status.isServerStarted || isLoading"
      >
        <span v-if="isLoading" class="loading"></span>
        <span v-else>⏹️</span>
        Stop HPS Server
      </button>

      <button 
        class="btn-secondary" 
        @click="clearLogs"
      >
        🗑️ Clear Logs
      </button>
    </div>

    <!-- Test Request Section -->
    <div class="test-request-section">
      <h2>🧪 Test HTTP Request</h2>
      <p style="color: #666; margin-bottom: 20px;">
        Send a test HTTP request directly from this server (not through BLE) to verify functionality.
      </p>

      <div class="form-group">
        <label>Method</label>
        <select v-model="testRequest.method">
          <option>GET</option>
          <option>POST</option>
          <option>PUT</option>
          <option>DELETE</option>
          <option>HEAD</option>
        </select>
      </div>

      <div class="form-group">
        <label>URL</label>
        <input 
          v-model="testRequest.url" 
          type="text" 
          placeholder="https://api.example.com/data"
        />
      </div>

      <div class="form-group">
        <label>Headers (JSON format)</label>
        <textarea 
          v-model="testRequest.headers" 
          placeholder='{"Content-Type": "application/json"}'
        ></textarea>
      </div>

      <div class="form-group" v-if="testRequest.method !== 'GET' && testRequest.method !== 'HEAD'">
        <label>Body (JSON or text)</label>
        <textarea 
          v-model="testRequest.body" 
          placeholder='{"key": "value"}'
        ></textarea>
      </div>

      <button 
        class="btn-primary" 
        @click="sendTestRequest"
        :disabled="!testRequest.url || isTestLoading"
      >
        <span v-if="isTestLoading" class="loading"></span>
        <span v-else>📤</span>
        Send Test Request
      </button>

      <!-- Response Section -->
      <div v-if="testResponse" class="response-section">
        <h4>Response (Status: {{ testResponse.statusCode }})</h4>
        <div class="response-content">{{ formatResponse(testResponse) }}</div>
      </div>
    </div>
  </div>

  <!-- Logs Section -->
  <div class="container">
    <div class="logs-section">
      <div class="logs-header">
        <h3>📝 Server Logs</h3>
      </div>

      <div v-if="logs.length === 0" class="empty-logs">
        No logs yet. Start the server to see activity.
      </div>

      <div v-else>
        <div 
          v-for="log in logs" 
          :key="log.timestamp" 
          class="log-entry" 
          :class="log.type"
        >
          <span class="log-timestamp">{{ formatTimestamp(log.timestamp) }}</span>
          <span class="log-message">{{ log.message }}</span>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
export default {
  name: 'App',
  data() {
    return {
      status: {
        isServerStarted: false,
        isAdvertising: false,
        bluetoothState: 'unknown',
        connectedClients: 0,
        clients: []
      },
      logs: [],
      testRequest: {
        method: 'GET',
        url: 'https://httpbin.org/get',
        headers: '{}',
        body: ''
      },
      testResponse: null,
      isLoading: false,
      isTestLoading: false,
      pollInterval: null
    };
  },
  mounted() {
    this.fetchStatus();
    this.fetchLogs();
    
    // Poll for updates every 2 seconds
    this.pollInterval = setInterval(() => {
      this.fetchStatus();
      this.fetchLogs();
    }, 2000);
  },
  beforeUnmount() {
    if (this.pollInterval) {
      clearInterval(this.pollInterval);
    }
  },
  methods: {
    async fetchStatus() {
      try {
        const response = await fetch('/api/status');
        this.status = await response.json();
      } catch (error) {
        console.error('Error fetching status:', error);
      }
    },
    
    async fetchLogs() {
      try {
        const response = await fetch('/api/logs');
        this.logs = await response.json();
      } catch (error) {
        console.error('Error fetching logs:', error);
      }
    },
    
    async startServer() {
      this.isLoading = true;
      try {
        const response = await fetch('/api/start', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' }
        });
        
        const result = await response.json();
        
        if (!response.ok) {
          alert(`Error: ${result.error}`);
        }
        
        await this.fetchStatus();
        await this.fetchLogs();
      } catch (error) {
        alert(`Error starting server: ${error.message}`);
      } finally {
        this.isLoading = false;
      }
    },
    
    async stopServer() {
      this.isLoading = true;
      try {
        const response = await fetch('/api/stop', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' }
        });
        
        const result = await response.json();
        
        if (!response.ok) {
          alert(`Error: ${result.error}`);
        }
        
        await this.fetchStatus();
        await this.fetchLogs();
      } catch (error) {
        alert(`Error stopping server: ${error.message}`);
      } finally {
        this.isLoading = false;
      }
    },
    
    async clearLogs() {
      try {
        await fetch('/api/logs/clear', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' }
        });
        
        await this.fetchLogs();
      } catch (error) {
        alert(`Error clearing logs: ${error.message}`);
      }
    },
    
    async sendTestRequest() {
      this.isTestLoading = true;
      this.testResponse = null;
      
      try {
        let headers = {};
        if (this.testRequest.headers.trim()) {
          headers = JSON.parse(this.testRequest.headers);
        }
        
        let body = null;
        if (this.testRequest.body.trim() && 
            this.testRequest.method !== 'GET' && 
            this.testRequest.method !== 'HEAD') {
          try {
            body = JSON.parse(this.testRequest.body);
          } catch {
            body = this.testRequest.body;
          }
        }
        
        const response = await fetch('/api/test-request', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            url: this.testRequest.url,
            method: this.testRequest.method,
            headers,
            body
          })
        });
        
        this.testResponse = await response.json();
        await this.fetchLogs();
      } catch (error) {
        alert(`Error sending test request: ${error.message}`);
      } finally {
        this.isTestLoading = false;
      }
    },
    
    formatTimestamp(timestamp) {
      const date = new Date(timestamp);
      return date.toLocaleTimeString();
    },
    
    formatResponse(response) {
      return JSON.stringify(response, null, 2);
    },
    
    getBluetoothStatusClass() {
      const state = this.status.bluetoothState?.toLowerCase();
      if (state === 'poweredon') return 'success';
      if (state === 'poweredoff') return 'error';
      return 'warning';
    }
  }
};
</script>
