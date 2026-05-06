# Reverse Connection (Cloud Mode)

Mobilerun Portal can initiate an outbound WebSocket connection to a host (used by Mobilerun Cloud). This keeps the device reachable even when it is behind NAT or on mobile networks.

## Enable in the app

1. Open **Settings** in the Portal app.
2. Under **Reverse Connection**, enter the host URL (ws/wss).
3. Optional: enter a token (sent as a Bearer token).
4. Toggle **Connect to Host**.

## Alternative method (Simplest)

Press Connect to Mobilerun button in portal main page.
---

Mobilerun default host URL:

```
wss://api.mobilerun.ai/v1/providers/personal/join
```

The `{deviceId}` placeholder is replaced automatically if present.

## Headers sent by the device

When connecting, the device includes metadata headers:

- `Authorization: Bearer <token>` (if set)
- `X-User-ID`
- `X-Device-ID`
- `X-Device-Name`
- `X-Device-Country`
- `X-Remote-Device-Key` (if configured)

## Command format

Reverse connection uses the same JSON-RPC-style messages as the local WebSocket API:

```json
{
  "id": "uuid-or-number",
  "method": "tap",
  "params": { "x": 200, "y": 400 }
}
```

Responses include `status` and `result` or `error`.

Live device events are sent separately as JSON-RPC-style notifications with no `id`. See
[WebSocket Events](websocket-events.md) and [Triggers and Events](triggers.md) for the event
contract.

## Trigger Management

Reverse connection supports the same trigger JSON-RPC methods as the local WebSocket API:

- `triggers/catalog`
- `triggers/status`
- `triggers/rules/list`
- `triggers/rules/get`
- `triggers/rules/save`
- `triggers/rules/delete`
- `triggers/rules/setEnabled`
- `triggers/rules/test`
- `triggers/runs/list`
- `triggers/runs/delete`
- `triggers/runs/clear`

These `triggers/*` methods are headless-safe and remain available even if the Accessibility Service is disconnected. See [Triggers and Events](triggers.md) for exact params, `TriggerJson` payload shape, and ADB equivalents.

## Device Events

While reverse connection is active, Portal forwards live device events automatically as unsolicited
notifications:

```json
{
  "method": "events/device",
  "params": {
    "type": "APP_ENTERED",
    "timestamp": 1234567890123,
    "payload": {
      "package": "com.example.app"
    }
  }
}
```

Notes:

- `params` is the same event object used by the local WebSocket event stream: `type`, `timestamp`,
  and optional `payload`.
- Local WebSocket defaults to raw `{ "type": "...", "timestamp": ..., "payload": ... }` frames.
- Local listeners can opt into this same `events/device` envelope via `?eventFormat=rpc`.
- `?eventFormat=legacy` is still accepted as an explicit request for the default local format.
- Delivery is live-only and best-effort. Events are not queued or replayed after reconnect.
- Event filtering still follows the existing Portal event toggles.

## App control

Reverse connection supports the same app commands as local WebSocket:

- `app` with optional `stopBeforeLaunch` (default `false`)
- `app/stop` to request a best-effort stop

Example:

```json
{
  "id": "1",
  "method": "app",
  "params": { "package": "com.example.app", "stopBeforeLaunch": true }
}
```

```json
{
  "id": "2",
  "method": "app/stop",
  "params": { "package": "com.example.app" }
}
```

## Streaming (WebRTC)

Streaming commands are only supported over reverse connection.

### Server → device

- `stream/start`
  - Params: `width`, `height`, `fps`, `sessionId`, `waitForOffer`, `iceServers`
- `stream/stop`
- `webrtc/answer` (for device-generated offers)
  - Params: `sdp`
- `webrtc/offer` (when `waitForOffer=true`)
  - Params: `sdp`, `sessionId`
- `webrtc/ice`
  - Params: `candidate`, `sdpMid`, `sdpMLineIndex`, `sessionId`

`iceServers` is an array of objects with `urls` and optional `username`/`credential`.

### Device → server

- `events/device`
  - Params: exact Portal event object `{ type, timestamp, payload? }`
- `stream/ready` (sent when capture is ready)
  - Params: `sessionId`
- `webrtc/offer` (device-generated offer)
  - Params: `sdp`
- `webrtc/ice`
  - Params: `candidate`, `sdpMid`, `sdpMLineIndex`, `sessionId`
- `stream/error`
  - Params: `error`, `message`, `sessionId`
- `stream/stopped`
  - Params: `reason`, `sessionId`

### Streaming notes

- On Android 13+, notification permission is required to show the background streaming prompt.
- **Auto-accept Screen Share** in Settings can click the MediaProjection dialog automatically.
- `stream/start` may return `prompting_user` or `waiting_for_user_notification_tap` while waiting for permission.

## Configure via ContentProvider (optional)

```bash
adb shell content insert --uri content://com.mobilerun.portal/configure_reverse_connection --bind url_base64:s:"d3NzOi8vYXBpLm1vYmlsZXJ1bi5haS92MS9wcm92aWRlcnMvcGVyc29uYWwvam9pbg==" --bind token_base64:s:"WU9VUl9UT0tFTg==" --bind enabled:b:true
```

The `check_event_streams.py` smoke script uses the same base64-backed provider fields under
the hood and restores the previous reverse URL/token and running state during cleanup when
`run-as` is available.

## Manual Smoke Test

You can manually verify reverse event forwarding with the workspace test server
`test_reverse_server.py`:

1. Start the server on your machine.
2. Point Portal reverse connection to that host and connect the device.
3. Trigger a notification, unlock, app switch, or network change on the device.
4. Verify the server logs show unsolicited messages with `"method":"events/device"` and the
   expected `params.type` / `params.payload`.
