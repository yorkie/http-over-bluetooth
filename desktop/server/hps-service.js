import bleno from '@abandonware/bleno';
import axios from 'axios';
import * as HPS from './hps-constants.js';

const { Characteristic, Descriptor } = bleno;

/**
 * HTTP Proxy Service implementation using bleno.
 * This service allows BLE clients to send HTTP requests through this server.
 */
export class HttpProxyService {
    constructor(options = {}) {
        // Request state
        this.uri = '';
        this.headers = {};
        this.body = null;
        this.statusCode = 0;
        this.responseHeaders = {};
        this.responseBody = null;
        this.isHttps = false;
        this.certificateValidated = false;
        this.deviceName = options.deviceName || 'HPS Server';
        
        // Multi-part write buffers for handling offset-based writes
        this.uriBuffer = null;
        this.headersBuffer = null;
        this.bodyBuffer = null;
        
        // Notification state
        this.statusCodeUpdateCallback = null;
        
        // Event callbacks
        this.onRequestReceived = null;
        this.onResponseSent = null;
        this.onError = null;
        
        this.initializeCharacteristics();
    }
    getDeviceName() {
        return this.deviceName;
    }
    
    initializeCharacteristics() {
        // URI Characteristic (Write)
        this.uriCharacteristic = new Characteristic({
            uuid: HPS.URI_CHARACTERISTIC_UUID,
            properties: ['write'],
            onWriteRequest: (data, offset, withoutResponse, callback) => {
                try {
                    // Handle multi-part writes with offset
                    if (offset === 0) {
                        // Start new write
                        this.uriBuffer = Buffer.from(data);
                    } else if (this.uriBuffer && offset === this.uriBuffer.length) {
                        // Append to existing buffer
                        this.uriBuffer = Buffer.concat([this.uriBuffer, data]);
                    } else {
                        // Invalid offset
                        this.log(`Invalid URI write offset: ${offset}`);
                        callback(Characteristic.RESULT_INVALID_OFFSET);
                        return;
                    }
                    this.uri = this.uriBuffer.toString('utf8');
                    this.log(`URI set to: ${this.uri} (offset: ${offset}, length: ${data.length})`);
                    callback(Characteristic.RESULT_SUCCESS);
                } catch (error) {
                    this.log(`Error setting URI: ${error.message}`);
                    callback(Characteristic.RESULT_UNLIKELY_ERROR);
                }
            }
        });
        
        // HTTP Headers Characteristic (Read, Write)
        this.headersCharacteristic = new Characteristic({
            uuid: HPS.HTTP_HEADERS_CHARACTERISTIC_UUID,
            properties: ['read', 'write'],
            onReadRequest: (offset, callback) => {
                try {
                    const headersString = this.serializeHeaders(this.responseHeaders);
                    const data = Buffer.from(headersString, 'utf8');
                    // Handle offset for multi-part reads
                    const responseData = offset < data.length ? data.slice(offset) : Buffer.alloc(0);
                    this.log(`Sending headers: ${responseData.length} bytes (offset: ${offset}, total: ${data.length})`);
                    callback(Characteristic.RESULT_SUCCESS, responseData);
                } catch (error) {
                    callback(Characteristic.RESULT_UNLIKELY_ERROR);
                }
            },
            onWriteRequest: (data, offset, withoutResponse, callback) => {
                try {
                    // Handle multi-part writes with offset
                    if (offset === 0) {
                        // Start new write
                        this.headersBuffer = Buffer.from(data);
                    } else if (this.headersBuffer && offset === this.headersBuffer.length) {
                        // Append to existing buffer
                        this.headersBuffer = Buffer.concat([this.headersBuffer, data]);
                    } else {
                        // Invalid offset
                        this.log(`Invalid headers write offset: ${offset}`);
                        callback(Characteristic.RESULT_INVALID_OFFSET);
                        return;
                    }
                    this.headers = this.parseHeaders(this.headersBuffer.toString('utf8'));
                    this.log(`Headers set: ${JSON.stringify(this.headers)} (offset: ${offset}, length: ${data.length})`);
                    callback(Characteristic.RESULT_SUCCESS);
                } catch (error) {
                    this.log(`Error setting headers: ${error.message}`);
                    callback(Characteristic.RESULT_UNLIKELY_ERROR);
                }
            }
        });
        
        // HTTP Status Code Characteristic (Read, Notify)
        this.statusCodeCharacteristic = new Characteristic({
            uuid: HPS.HTTP_STATUS_CODE_CHARACTERISTIC_UUID,
            properties: ['read', 'notify'],
            // descriptors: [
            //     new Descriptor({
            //         uuid: '2902', // Client Characteristic Configuration
            //         value: Buffer.alloc(2)
            //     })
            // ],
            onReadRequest: (offset, callback) => {
                const data = this.serializeStatusCode(this.statusCode);
                callback(Characteristic.RESULT_SUCCESS, data);
            },
            onSubscribe: (maxValueSize, updateValueCallback) => {
                this.log('Client subscribed to status code notifications');
                this.statusCodeUpdateCallback = updateValueCallback;
            },
            onUnsubscribe: () => {
                this.log('Client unsubscribed from status code notifications');
                this.statusCodeUpdateCallback = null;
            }
        });
        
        // HTTP Entity Body Characteristic (Read, Write)
        this.entityBodyCharacteristic = new Characteristic({
            uuid: HPS.HTTP_ENTITY_BODY_CHARACTERISTIC_UUID,
            properties: ['read', 'write'],
            onReadRequest: (offset, callback) => {
                try {
                    const data = this.responseBody || Buffer.alloc(0);
                    // Handle offset for multi-part reads
                    const responseData = offset < data.length ? data.slice(offset) : Buffer.alloc(0);
                    this.log(`Sending body: ${responseData.length} bytes (offset: ${offset}, total: ${data.length})`);
                    callback(Characteristic.RESULT_SUCCESS, responseData);
                } catch (error) {
                    callback(Characteristic.RESULT_UNLIKELY_ERROR);
                }
            },
            onWriteRequest: (data, offset, withoutResponse, callback) => {
                try {
                    // Handle multi-part writes with offset
                    if (offset === 0) {
                        // Start new write
                        this.bodyBuffer = Buffer.from(data);
                    } else if (this.bodyBuffer && offset === this.bodyBuffer.length) {
                        // Append to existing buffer
                        this.bodyBuffer = Buffer.concat([this.bodyBuffer, data]);
                    } else {
                        // Invalid offset
                        this.log(`Invalid body write offset: ${offset}`);
                        callback(Characteristic.RESULT_INVALID_OFFSET);
                        return;
                    }
                    this.body = this.bodyBuffer;
                    this.log(`Body set: ${this.body.length} bytes (offset: ${offset}, length: ${data.length})`);
                    callback(Characteristic.RESULT_SUCCESS);
                } catch (error) {
                    this.log(`Error setting body: ${error.message}`);
                    callback(Characteristic.RESULT_UNLIKELY_ERROR);
                }
            }
        });
        
        // HTTP Control Point Characteristic (Write)
        this.controlPointCharacteristic = new Characteristic({
            uuid: HPS.HTTP_CONTROL_POINT_CHARACTERISTIC_UUID,
            properties: ['write'],
            onWriteRequest: (data, offset, withoutResponse, callback) => {
                try {
                    const opcode = data[0];
                    this.log(`Control point opcode received: 0x${opcode.toString(16)}`);
                    this.executeHttpRequest(opcode);
                    callback(Characteristic.RESULT_SUCCESS);
                } catch (error) {
                    this.log(`Error executing request: ${error.message}`);
                    callback(Characteristic.RESULT_UNLIKELY_ERROR);
                }
            }
        });
        
        // HTTPS Security Characteristic (Read)
        this.httpsSecurityCharacteristic = new Characteristic({
            uuid: HPS.HTTPS_SECURITY_CHARACTERISTIC_UUID,
            properties: ['read'],
            onReadRequest: (offset, callback) => {
                const value = this.certificateValidated 
                    ? HPS.HTTPS_SECURITY_CERTIFICATE_VALIDATED 
                    : HPS.HTTPS_SECURITY_CERTIFICATE_NOT_VALIDATED;
                callback(Characteristic.RESULT_SUCCESS, Buffer.from([value]));
            }
        });
    }
    
    resetRequestBuffers() {
        // Reset write buffers for next request
        this.uriBuffer = null;
        this.headersBuffer = null;
        this.bodyBuffer = null;
    }
    
    async executeHttpRequest(opcode) {
        try {
            if (opcode === HPS.OPCODE_HTTP_REQUEST_CANCEL) {
                this.log('Request cancelled');
                this.resetRequestBuffers();
                return;
            }
            
            const method = HPS.OPCODE_TO_METHOD[opcode];
            this.isHttps = HPS.isHttpsOpcode(opcode);
            
            if (!method) {
                throw new Error(`Invalid opcode: 0x${opcode.toString(16)}`);
            }
            
            if (!this.uri) {
                throw new Error('URI not set');
            }
            
            this.log(`Executing ${this.isHttps ? 'HTTPS' : 'HTTP'} ${method} request to ${this.uri}`);
            
            if (this.onRequestReceived) {
                this.onRequestReceived({
                    uri: this.uri,
                    method,
                    headers: this.headers,
                    body: this.body,
                    isHttps: this.isHttps
                });
            }
            
            // Execute the actual HTTP request
            const config = {
                method: method.toLowerCase(),
                url: this.uri,
                headers: this.headers,
                data: this.body,
                validateStatus: () => true, // Accept any status code
                maxRedirects: 5,
                timeout: 30000
            };
            
            const response = await axios(config);
            
            // Store response
            this.statusCode = response.status;
            this.responseHeaders = response.headers || {};
            
            // Convert response data to Buffer, handling different types
            if (Buffer.isBuffer(response.data)) {
                this.responseBody = response.data;
            } else if (typeof response.data === 'string') {
                this.responseBody = Buffer.from(response.data, 'utf8');
            } else if (response.data !== null && response.data !== undefined) {
                this.responseBody = Buffer.from(JSON.stringify(response.data), 'utf8');
            } else {
                this.responseBody = Buffer.alloc(0);
            }
            
            this.certificateValidated = this.isHttps; // Simplified
            
            this.log(`Response received: ${this.statusCode}`);
            
            // Notify client of status code
            if (this.statusCodeUpdateCallback) {
                const statusData = this.serializeStatusCode(this.statusCode);
                this.statusCodeUpdateCallback(statusData);
            }
            
            if (this.onResponseSent) {
                this.onResponseSent({
                    statusCode: this.statusCode,
                    headers: this.responseHeaders,
                    body: this.responseBody,
                    isHttps: this.isHttps,
                    certificateValidated: this.certificateValidated
                });
            }
            
            // Reset buffers for next request
            this.resetRequestBuffers();
            
        } catch (error) {
            this.log(`HTTP request error: ${error.message}`);
            this.statusCode = 500;
            this.responseBody = Buffer.from(error.message);
            
            // Notify client of error status code
            if (this.statusCodeUpdateCallback) {
                const statusData = this.serializeStatusCode(this.statusCode);
                this.statusCodeUpdateCallback(statusData);
            }
            
            if (this.onError) {
                this.onError(error);
            }
            
            // Reset buffers for next request even on error
            this.resetRequestBuffers();
        }
    }
    
    parseHeaders(headerString) {
        const headers = {};
        if (!headerString) return headers;
        
        headerString.split('\r\n').forEach(line => {
            const colonIndex = line.indexOf(':');
            if (colonIndex > 0) {
                const key = line.substring(0, colonIndex).trim();
                const value = line.substring(colonIndex + 1).trim();
                headers[key] = value;
            }
        });
        return headers;
    }
    
    serializeHeaders(headers) {
        return Object.entries(headers)
            .map(([key, value]) => `${key}: ${value}`)
            .join('\r\n');
    }
    
    serializeStatusCode(statusCode) {
        // Status code is encoded as 16-bit little-endian unsigned integer
        // Clamp to valid range [0, 65535]
        const code = Math.max(0, Math.min(65535, statusCode));
        return Buffer.from([
            code & 0xFF,
            (code >> 8) & 0xFF
        ]);
    }
    
    log(message) {
        console.log(`[HPS Service] ${message}`);
    }
    
    getService() {
        return new bleno.PrimaryService({
            uuid: HPS.HTTP_PROXY_SERVICE_UUID,
            characteristics: [
                this.uriCharacteristic,
                this.headersCharacteristic,
                this.statusCodeCharacteristic,
                this.entityBodyCharacteristic,
                this.controlPointCharacteristic,
                this.httpsSecurityCharacteristic
            ]
        });
    }
}
