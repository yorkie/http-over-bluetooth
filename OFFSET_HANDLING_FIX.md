# BLE Offset Handling Fix

## Problem Description

When using the Android sample to connect to the desktop server and sending test requests, the headers and body content displayed only the first part repeatedly. This was caused by incorrect handling of BLE characteristic read/write operations with offsets.

## Root Cause

BLE has a Maximum Transmission Unit (MTU) limitation, typically between 20-512 bytes. When data exceeds the MTU, it must be split into multiple read/write operations with different offsets. Both Android and Desktop servers were not handling these offsets correctly:

### Desktop Server Issues (hps-service.js)

The `onReadRequest` handlers for headers and body characteristics completely ignored the `offset` parameter and always returned the full buffer starting from offset 0. This caused the client to receive repeated data when reading large characteristics.

**Before:**
```javascript
onReadRequest: (offset, callback) => {
    const data = Buffer.from(headersString, 'utf8');
    callback(Characteristic.RESULT_SUCCESS, data);  // Always returns full data!
}
```

**After:**
```javascript
onReadRequest: (offset, callback) => {
    const data = Buffer.from(headersString, 'utf8');
    const responseData = offset < data.length ? data.slice(offset) : Buffer.alloc(0);
    callback(Characteristic.RESULT_SUCCESS, responseData);  // Returns data from offset
}
```

### Android Server Issues (HttpOverBleServer.kt)

The `onCharacteristicWriteRequest` handler ignored the `offset` parameter for URI, headers, and body writes. It simply replaced the entire value instead of appending data at the correct offset.

**Before:**
```kotlin
HttpProxyServiceConstants.URI_CHARACTERISTIC_UUID -> {
    clientData.uri = String(value, Charsets.UTF_8)  // Always replaces!
}
```

**After:**
```kotlin
HttpProxyServiceConstants.URI_CHARACTERISTIC_UUID -> {
    if (offset == 0) {
        clientData.uriBuffer = value
    } else if (clientData.uriBuffer != null && offset == clientData.uriBuffer!!.size) {
        clientData.uriBuffer = clientData.uriBuffer!! + value
    } else {
        // Invalid offset error
    }
    clientData.uri = String(clientData.uriBuffer!!, Charsets.UTF_8)
}
```

## Solution

### Desktop Server Changes

1. **Read Operations**: Modified `onReadRequest` handlers to respect the offset parameter:
   - Headers characteristic: `data.slice(offset)` returns remaining data from offset
   - Body characteristic: `data.slice(offset)` returns remaining data from offset
   - Returns empty buffer when offset >= data length

2. **Write Operations**: Added multi-part write buffers to accumulate data:
   - Added `uriBuffer`, `headersBuffer`, `bodyBuffer` to track partial writes
   - When `offset === 0`: Start new write, create new buffer
   - When `offset === buffer.length`: Append data to existing buffer
   - Otherwise: Return `RESULT_INVALID_OFFSET`

3. **Buffer Management**: Added `resetRequestBuffers()` method:
   - Called after successful request completion
   - Called after error during request execution
   - Called when request is cancelled
   - Ensures buffers are clean for next request

### Android Server Changes

1. **Write Operations**: Modified `onCharacteristicWriteRequest` to handle offsets:
   - Added write buffers to `ClientRequestData` class
   - Accumulate data across multiple write operations with proper offset checking
   - Return `GATT_INVALID_OFFSET` for invalid offsets

2. **Buffer Management**: Reset buffers after request completion:
   - After successful HTTP request execution
   - After error during HTTP request
   - When request is cancelled via control point

## Testing

### Manual Testing Steps

1. **Test with Large Headers**:
   - Android Client → Desktop Server
   - Send request with headers exceeding 512 bytes (e.g., 10-15 headers)
   - Verify headers are correctly received and parsed on server
   - Verify response headers are correctly received on client

2. **Test with Large Body**:
   - Android Client → Desktop Server
   - Send POST request with body > 512 bytes
   - Verify body is correctly received on server
   - Verify response body > 512 bytes is correctly received on client

3. **Test Request Sequence**:
   - Send multiple requests in sequence
   - Verify each request has clean buffers (no data from previous request)
   - Verify no memory leaks from accumulated buffers

4. **Test Error Cases**:
   - Test invalid offset (simulate by modifying client temporarily)
   - Verify server returns proper error code
   - Verify server doesn't crash

### Expected Behavior

**Before Fix:**
- Large headers/body show first chunk repeated multiple times
- Data corruption when size > MTU
- Response may contain truncated or repeated data

**After Fix:**
- Complete headers/body transferred correctly regardless of size
- Each chunk read/written at correct offset
- Clean buffers between requests
- Proper error handling for invalid offsets

### Test URLs for Large Responses

```
# Returns large JSON response (good for testing body reads)
https://jsonplaceholder.typicode.com/posts

# Returns large response with many headers
https://httpbin.org/response-headers?Header1=Value1&Header2=Value2&...

# POST endpoint for testing large body writes
https://httpbin.org/post
```

### Verification Points

1. **Desktop Server Logs**:
   ```
   [HPS Service] Sending headers: 250 bytes (offset: 0, total: 750)
   [HPS Service] Sending headers: 250 bytes (offset: 250, total: 750)
   [HPS Service] Sending headers: 250 bytes (offset: 500, total: 750)
   ```

2. **Android Server Logs**:
   ```
   Received URI: https://... (offset: 0, length: 100)
   Received headers: {...} (offset: 0, length: 300)
   Received body: 512 bytes (offset: 0, length: 512)
   Received body: 1024 bytes (offset: 512, length: 512)
   ```

## BLE MTU Information

### Default MTU Values
- **Android**: 23 bytes (20 bytes payload)
- **iOS**: 185 bytes (182 bytes payload)
- **Desktop (macOS)**: 185 bytes typically

### MTU Negotiation
After BLE connection, devices can negotiate a larger MTU (up to 517 bytes). However, the implementation should always handle any MTU size correctly by:
1. Respecting offset parameter in all read/write operations
2. Accumulating multi-part writes
3. Slicing multi-part reads from correct offset

## Related Files Modified

- `desktop/server/hps-service.js`: Desktop server BLE service implementation
- `android/http-over-bluetooth/src/main/java/com/rokid/mlabs/httpoverble/server/HttpOverBleServer.kt`: Android server implementation

## Backward Compatibility

The fix is backward compatible:
- Single-packet transfers (data < MTU) work exactly as before
- Multi-packet transfers now work correctly
- No API changes required
- No changes to client code needed

## Performance Impact

Minimal performance impact:
- Small overhead for buffer management
- Offset calculation is O(1)
- Buffer concatenation is optimized by Buffer/ByteArray implementations
- Memory usage increases temporarily during multi-part transfers but buffers are cleaned up

## Future Improvements

Potential enhancements:
1. Add configurable timeout for incomplete multi-part writes
2. Add metrics for tracking MTU sizes and transfer patterns
3. Implement prepared writes for better error recovery
4. Add unit tests for offset handling logic (requires BLE stack mocking)

## References

- [Bluetooth HPS Specification 1.0](https://www.bluetooth.com/specifications/specs/http-proxy-service-1-0/)
- [Android BLE GATT Documentation](https://developer.android.com/guide/topics/connectivity/bluetooth-le)
- [BLE MTU Negotiation](https://punchthrough.com/maximizing-ble-throughput-part-2-use-larger-att-mtu-2/)
