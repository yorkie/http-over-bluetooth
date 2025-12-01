package com.example.httpoverble.common

import java.util.UUID

/**
 * Constants for HTTP Proxy Service based on Bluetooth HPS 1.0 specification.
 * Reference: https://www.bluetooth.com/specifications/specs/http-proxy-service-1-0/
 */
object HttpProxyServiceConstants {
    
    // HTTP Proxy Service UUID
    val HTTP_PROXY_SERVICE_UUID: UUID = UUID.fromString("00001823-0000-1000-8000-00805f9b34fb")
    
    // Characteristic UUIDs
    val URI_CHARACTERISTIC_UUID: UUID = UUID.fromString("00002ab6-0000-1000-8000-00805f9b34fb")
    val HTTP_HEADERS_CHARACTERISTIC_UUID: UUID = UUID.fromString("00002ab7-0000-1000-8000-00805f9b34fb")
    val HTTP_STATUS_CODE_CHARACTERISTIC_UUID: UUID = UUID.fromString("00002ab8-0000-1000-8000-00805f9b34fb")
    val HTTP_ENTITY_BODY_CHARACTERISTIC_UUID: UUID = UUID.fromString("00002ab9-0000-1000-8000-00805f9b34fb")
    val HTTP_CONTROL_POINT_CHARACTERISTIC_UUID: UUID = UUID.fromString("00002aba-0000-1000-8000-00805f9b34fb")
    val HTTPS_SECURITY_CHARACTERISTIC_UUID: UUID = UUID.fromString("00002abb-0000-1000-8000-00805f9b34fb")
    
    // Client Characteristic Configuration Descriptor UUID
    val CLIENT_CHARACTERISTIC_CONFIG_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    
    // HTTP Control Point opcodes
    const val OPCODE_HTTP_GET_REQUEST: Byte = 0x01
    const val OPCODE_HTTP_HEAD_REQUEST: Byte = 0x02
    const val OPCODE_HTTP_POST_REQUEST: Byte = 0x03
    const val OPCODE_HTTP_PUT_REQUEST: Byte = 0x04
    const val OPCODE_HTTP_DELETE_REQUEST: Byte = 0x05
    const val OPCODE_HTTPS_GET_REQUEST: Byte = 0x06
    const val OPCODE_HTTPS_HEAD_REQUEST: Byte = 0x07
    const val OPCODE_HTTPS_POST_REQUEST: Byte = 0x08
    const val OPCODE_HTTPS_PUT_REQUEST: Byte = 0x09
    const val OPCODE_HTTPS_DELETE_REQUEST: Byte = 0x0A
    const val OPCODE_HTTP_REQUEST_CANCEL: Byte = 0x0B
    
    // HTTPS Security values
    const val HTTPS_SECURITY_CERTIFICATE_NOT_VALIDATED: Byte = 0x00
    const val HTTPS_SECURITY_CERTIFICATE_VALIDATED: Byte = 0x01
    
    // Maximum data sizes
    const val MAX_URI_LENGTH = 512
    const val MAX_HEADERS_LENGTH = 512
    const val MAX_BODY_LENGTH = 512
}
