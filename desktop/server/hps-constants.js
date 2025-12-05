/**
 * Constants for HTTP Proxy Service based on Bluetooth HPS 1.0 specification.
 * Reference: https://www.bluetooth.com/specifications/specs/http-proxy-service-1-0/
 */

// HTTP Proxy Service UUID
export const HTTP_PROXY_SERVICE_UUID = '00001823-0000-1000-8000-00805f9b34fb';

// Characteristic UUIDs (short form)
export const URI_CHARACTERISTIC_UUID = '00002ab6-0000-1000-8000-00805f9b34fb';
export const HTTP_HEADERS_CHARACTERISTIC_UUID = '00002ab7-0000-1000-8000-00805f9b34fb';
export const HTTP_STATUS_CODE_CHARACTERISTIC_UUID = '00002ab8-0000-1000-8000-00805f9b34fb';
export const HTTP_ENTITY_BODY_CHARACTERISTIC_UUID = '00002ab9-0000-1000-8000-00805f9b34fb';
export const HTTP_CONTROL_POINT_CHARACTERISTIC_UUID = '00002aba-0000-1000-8000-00805f9b34fb';
export const HTTPS_SECURITY_CHARACTERISTIC_UUID = '00002abb-0000-1000-8000-00805f9b34fb';

// HTTP Control Point opcodes
export const OPCODE_HTTP_GET_REQUEST = 0x01;
export const OPCODE_HTTP_HEAD_REQUEST = 0x02;
export const OPCODE_HTTP_POST_REQUEST = 0x03;
export const OPCODE_HTTP_PUT_REQUEST = 0x04;
export const OPCODE_HTTP_DELETE_REQUEST = 0x05;
export const OPCODE_HTTPS_GET_REQUEST = 0x06;
export const OPCODE_HTTPS_HEAD_REQUEST = 0x07;
export const OPCODE_HTTPS_POST_REQUEST = 0x08;
export const OPCODE_HTTPS_PUT_REQUEST = 0x09;
export const OPCODE_HTTPS_DELETE_REQUEST = 0x0A;
export const OPCODE_HTTP_REQUEST_CANCEL = 0x0B;

// HTTPS Security values
export const HTTPS_SECURITY_CERTIFICATE_NOT_VALIDATED = 0x00;
export const HTTPS_SECURITY_CERTIFICATE_VALIDATED = 0x01;

// Maximum data sizes
export const MAX_URI_LENGTH = 512;
export const MAX_HEADERS_LENGTH = 512;
export const MAX_BODY_LENGTH = 512;

// Opcode to method mapping
export const OPCODE_TO_METHOD = {
    [OPCODE_HTTP_GET_REQUEST]: 'GET',
    [OPCODE_HTTP_HEAD_REQUEST]: 'HEAD',
    [OPCODE_HTTP_POST_REQUEST]: 'POST',
    [OPCODE_HTTP_PUT_REQUEST]: 'PUT',
    [OPCODE_HTTP_DELETE_REQUEST]: 'DELETE',
    [OPCODE_HTTPS_GET_REQUEST]: 'GET',
    [OPCODE_HTTPS_HEAD_REQUEST]: 'HEAD',
    [OPCODE_HTTPS_POST_REQUEST]: 'POST',
    [OPCODE_HTTPS_PUT_REQUEST]: 'PUT',
    [OPCODE_HTTPS_DELETE_REQUEST]: 'DELETE',
};

// Check if opcode is HTTPS
export function isHttpsOpcode(opcode) {
    return opcode >= OPCODE_HTTPS_GET_REQUEST && opcode <= OPCODE_HTTPS_DELETE_REQUEST;
}
