# WebSocket Events

Mobilerun Portal includes a WebSocket server that broadcasts real-time events from the device, including notifications, app transitions, battery and power changes, user-present events, network changes, and SMS reception.

For the complete trigger and event contract, including every emitted `EventType`, every rule `TriggerSource`, and the payload keys for each device event, see [Triggers and Events](triggers.md).
Local WebSocket defaults to raw unsolicited device event frames. Reverse connection uses
`{"method":"events/device","params":...}`, and local listeners can opt into that same envelope
with `?eventFormat=rpc`. See [Reverse Connection](reverse-connection.md).

## Setup

### 1. Enable WebSocket Server

Open the Mobilerun Portal app and enable the WebSocket server in settings. The default port is `8081`.

### 2. Get the auth token

Local WebSocket access requires a token. You can copy it from the main screen or query via ADB:

```bash
adb shell content query --uri content://com.mobilerun.portal/auth_token
```

### 3. Set Up ADB Port Forwarding

Forward the WebSocket port from your device to your computer:

```bash
adb forward tcp:8081 tcp:8081
```

### 4. Connect

Connect to `ws://localhost:8081` using any WebSocket client and pass the token:

- Query param: `ws://localhost:8081/?token=YOUR_TOKEN`
- Or send `Authorization: Bearer YOUR_TOKEN` header
- Optional RPC local event frames: add `&eventFormat=rpc`

Make sure **Notification Access** is granted and the **Notification** event toggle is enabled in Settings.

## Event Format

Local WebSocket unsolicited device events follow this raw structure by default:

This WebSocket also supports JSON-RPC-style commands; see [Local API](local-api.md) for the command format and methods.

```json
{
  "type": "EVENT_TYPE",
  "timestamp": 1234567890123,
  "payload": { ... }
}
```

## Event Types

Portal currently emits these event families over WebSocket:

- Notification events: `NOTIFICATION`, `NOTIFICATION_POSTED`, `NOTIFICATION_REMOVED`
- App events: `APP_ENTERED`, `APP_EXITED`
- Battery and power events: `BATTERY_LOW`, `BATTERY_OKAY`, `BATTERY_LEVEL_CHANGED`, `POWER_CONNECTED`, `POWER_DISCONNECTED`
- Device/user events: `USER_PRESENT`
- Network events: `NETWORK_CONNECTED`, `NETWORK_TYPE_CHANGED`
- Messaging events: `SMS_RECEIVED`
- Protocol/internal events: `PING`, `PONG`, `UNKNOWN`

The sections below keep the legacy notification and ping/pong examples. Use [Triggers and Events](triggers.md) as the source of truth for the full contract.

If you want the same envelope used by reverse connection, connect with `?eventFormat=rpc` and
expect `{"method":"events/device","params":...}`. `?eventFormat=legacy` is still accepted as
an explicit request for the default raw format. Raw `PING` / `PONG` compatibility also remains
available on the local socket.

### PING / PONG

Test the connection:

```json
// Send
{"type": "PING", "timestamp": 123456789}

// Receive
{"type": "PONG", "timestamp": 1234567890123, "payload": "pong"}
```

### NOTIFICATION

Fired when a notification is posted or removed on the device:

```json
{
  "type": "NOTIFICATION",
  "timestamp": 1234567890123,
  "payload": {
    "package": "com.example.app",
    "title": "New Message",
    "text": "You have a new message",
    "id": 12345,
    "tag": "",
    "is_ongoing": false,
    "post_time": 1234567890000,
    "key": "0|com.example.app|12345|null|10001"
  }
}
```

When a notification is removed, the payload includes a `removed` flag:

```json
{
  "type": "NOTIFICATION",
  "timestamp": 1234567890123,
  "payload": {
    "package": "com.example.app",
    "id": 12345,
    "key": "0|com.example.app|12345|null|10001",
    "removed": true
  }
}
```

## Python Example

Use the included test script to connect and listen for events:

```bash
# Install dependencies
pip install websockets

# Run the end-to-end smoke script (local default + local RPC + reverse)
python3 scripts/check_event_streams.py --mode both

# Run the test script (automatically sets up ADB forward)
python test_websocket.py 8081 YOUR_TOKEN

# Or set the token via environment variable
PORTAL_TOKEN=YOUR_TOKEN python test_websocket.py

# Or specify a custom port
python test_websocket.py 8082 YOUR_TOKEN

# Or explicitly request the reverse-style local RPC envelope
python test_websocket.py 8081 YOUR_TOKEN --rpc-events
```

The smoke script configures reverse connection through the ContentProvider's base64-backed
string fields (`url_base64`, `token_base64`, `service_key_base64`) so WebSocket URLs and
tokens survive shell parsing. When `run-as` is available, it snapshots the previous reverse
URL/token and restores them, along with the prior reverse-service running state, during
cleanup.

Example output:

```
Setting up ADB forward tcp:8081 -> tcp:8081...
✅ ADB forward established on port 8081
Connecting to ws://localhost:8081/?token=YOUR_TOKEN...
✅ Connected successfully!

Sending PING...
Waiting for response...
Received: {"type":"PONG","timestamp":1234567890123,"payload":"pong"}
✅ PING/PONG Test Passed

==================================================
Listening for events (Ctrl+C to stop)...
Trigger a notification on your phone to see it here!
==================================================

[NOTIFICATION] 1234567890123
  package: com.whatsapp
  title: John
  text: Hey, how are you?
  id: 12345
  is_ongoing: false
```

## Minimal Python Client

```python
import asyncio
import websockets
import json

async def listen():
    async with websockets.connect("ws://localhost:8081/?token=YOUR_TOKEN") as ws:
        while True:
            event = json.loads(await ws.recv())
            print(f"[{event['type']}] {event.get('payload', {})}")

asyncio.run(listen())
```

## Troubleshooting

| Issue | Solution |
|-------|----------|
| Connection refused | Ensure the app is running and WebSocket server is enabled in settings |
| No events received | Check that notification listener permission is granted for Mobilerun Portal |
| ADB forward fails | Make sure a device is connected via `adb devices` |
