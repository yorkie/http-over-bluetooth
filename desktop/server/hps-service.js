import bleno from '@abandonware/bleno';
import axios from 'axios';
import * as HPS from './hps-constants.js';

const { Characteristic, Descriptor } = bleno;

/**
 * HTTP Proxy Service implementation using bleno.
 * This service allows BLE clients to send HTTP requests through this server.
 */
export class HttpProxyService {
    // Packet protocol constants
    static FLAG_IS_FINAL = 0x01;
    static PACKET_HEADER_SIZE = 1;
    static DEFAULT_MTU = 23;
    static ATT_OVERHEAD = 3;
    
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
        
        // Packet reassembly buffers for handling packet-based writes
        this.uriPacketBuffer = null;
        this.headersPacketBuffer = null;
        this.bodyPacketBuffer = null;
        
        // MTU tracking (will be updated when client sends MTU via headers)
        this.negotiatedMtu = 510;  // Use 510 as default max packet size
        this.clientMtu = null;  // Will be set when client notifies MTU
        
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
                    // Packet-based protocol: extract header and payload
                    if (data.length === 0) {
                        this.log('Received empty URI packet');
                        callback(Characteristic.RESULT_SUCCESS);
                        return;
                    }
                    
                    const header = data[0];
                    const isFinal = (header & HttpProxyService.FLAG_IS_FINAL) !== 0;
                    const payload = data.slice(1);
                    
                    this.uriPacketBuffer = this.uriPacketBuffer ? 
                        Buffer.concat([this.uriPacketBuffer, payload]) : payload;
                    
                    if (isFinal) {
                        this.uri = this.uriPacketBuffer.toString('utf8');
                        this.log(`URI complete: ${this.uri} (${this.uriPacketBuffer.length} bytes)`);
                        this.uriPacketBuffer = null;
                    } else {
                        this.log(`URI packet (not final): ${payload.length} bytes, total: ${this.uriPacketBuffer.length}`);
                    }
                    
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
                    this.handlePacketizedRead(data, offset, callback);
                } catch (error) {
                    callback(Characteristic.RESULT_UNLIKELY_ERROR);
                }
            },
            onWriteRequest: (data, offset, withoutResponse, callback) => {
                try {
                    // Packet-based protocol: extract header and payload
                    if (data.length === 0) {
                        this.log('Received empty headers packet');
                        callback(Characteristic.RESULT_SUCCESS);
                        return;
                    }
                    
                    const header = data[0];
                    const isFinal = (header & HttpProxyService.FLAG_IS_FINAL) !== 0;
                    const payload = data.slice(1);
                    
                    this.headersPacketBuffer = this.headersPacketBuffer ? 
                        Buffer.concat([this.headersPacketBuffer, payload]) : payload;
                    
                    if (isFinal) {
                        this.headers = this.parseHeaders(this.headersPacketBuffer.toString('utf8'));
                        
                        // Check if client sent MTU in special header
                        if (this.headers['X-BLE-MTU']) {
                            const clientMtu = parseInt(this.headers['X-BLE-MTU']);
                            if (!isNaN(clientMtu) && clientMtu > 0) {
                                this.clientMtu = clientMtu;
                                this.log(`Client MTU set to: ${clientMtu}`);
                                // Remove the special header
                                delete this.headers['X-BLE-MTU'];
                            }
                        }
                        
                        this.log(`Headers complete: ${JSON.stringify(this.headers)} (${this.headersPacketBuffer.length} bytes)`);
                        this.headersPacketBuffer = null;
                    } else {
                        this.log(`Headers packet (not final): ${payload.length} bytes, total: ${this.headersPacketBuffer.length}`);
                    }
                    
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
                    this.handlePacketizedRead(data, offset, callback);
                } catch (error) {
                    callback(Characteristic.RESULT_UNLIKELY_ERROR);
                }
            },
            onWriteRequest: (data, offset, withoutResponse, callback) => {
                try {
                    // Packet-based protocol: extract header and payload
                    if (data.length === 0) {
                        this.log('Received empty body packet');
                        callback(Characteristic.RESULT_SUCCESS);
                        return;
                    }
                    
                    const header = data[0];
                    const isFinal = (header & HttpProxyService.FLAG_IS_FINAL) !== 0;
                    const payload = data.slice(1);
                    
                    this.bodyPacketBuffer = this.bodyPacketBuffer ? 
                        Buffer.concat([this.bodyPacketBuffer, payload]) : payload;
                    
                    if (isFinal) {
                        this.body = this.bodyPacketBuffer;
                        this.log(`Body complete: ${this.body.length} bytes`);
                        this.bodyPacketBuffer = null;
                    } else {
                        this.log(`Body packet (not final): ${payload.length} bytes, total: ${this.bodyPacketBuffer.length}`);
                    }
                    
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
                try {
                    const value = this.certificateValidated 
                        ? HPS.HTTPS_SECURITY_CERTIFICATE_VALIDATED 
                        : HPS.HTTPS_SECURITY_CERTIFICATE_NOT_VALIDATED;
                    const data = Buffer.from([value]);
                    this.handlePacketizedRead(data, offset, callback);
                } catch (error) {
                    callback(Characteristic.RESULT_UNLIKELY_ERROR);
                }
            }
        });
    }
    
    resetRequestBuffers() {
        // Reset packet buffers for next request
        this.uriPacketBuffer = null;
        this.headersPacketBuffer = null;
        this.bodyPacketBuffer = null;
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
    
    /**
     * Splits data into packets for reading.
     * Returns array of packets, each with header byte indicating if final.
     */
    splitDataIntoPackets(data) {
        const maxPacketSize = this.clientMtu || 510;  // Use client MTU or default 510
        const maxPayload = maxPacketSize - HttpProxyService.ATT_OVERHEAD - HttpProxyService.PACKET_HEADER_SIZE;
        
        if (maxPayload <= 0) {
            this.log(`Invalid packet size configuration: MTU=${maxPacketSize}`);
            // Fallback: single packet with all data
            return [Buffer.concat([Buffer.from([HttpProxyService.FLAG_IS_FINAL]), data])];
        }
        
        const packets = [];
        let offset = 0;
        
        while (offset < data.length) {
            const remainingBytes = data.length - offset;
            const payloadSize = Math.min(remainingBytes, maxPayload);
            const isFinal = (offset + payloadSize) >= data.length;
            
            const header = isFinal ? HttpProxyService.FLAG_IS_FINAL : 0x00;
            const payload = data.slice(offset, offset + payloadSize);
            const packet = Buffer.concat([Buffer.from([header]), payload]);
            
            packets.push(packet);
            offset += payloadSize;
        }
        
        this.log(`Split data into ${packets.length} packet(s), max payload: ${maxPayload} bytes`);
        return packets;
    }
    
    /**
     * Handles read requests with multi-packet support.
     * Serves packets based on offset, properly handling packet boundaries.
     */
    handlePacketizedRead(data, offset, callback) {
        try {
            const packets = this.splitDataIntoPackets(data);
            
            // Concatenate all packets
            const allPacketsData = Buffer.concat(packets);
            
            // Serve data from offset
            const responseData = offset < allPacketsData.length ? 
                allPacketsData.slice(offset) : Buffer.alloc(0);
            
            this.log(`Serving ${responseData.length} bytes from offset ${offset} (total: ${allPacketsData.length} bytes, ${packets.length} packets)`);
            callback(Characteristic.RESULT_SUCCESS, responseData);
        } catch (error) {
            this.log(`Error in packetized read: ${error.message}`);
            callback(Characteristic.RESULT_UNLIKELY_ERROR);
        }
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
