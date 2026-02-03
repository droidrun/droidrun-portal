# Reverse Connection (Cloud Mode)

Droidrun Portal can initiate an outbound WebSocket connection to a host (used by Mobilerun Cloud). This keeps the device reachable even when it is behind NAT or on mobile networks.

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
adb shell content insert --uri content://com.droidrun.portal/configure_reverse_connection --bind url:s:"wss://api.mobilerun.ai/v1/providers/personal/join" --bind token:s:"YOUR_TOKEN" --bind enabled:b:true
```
