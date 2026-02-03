# Local API

Droidrun Portal exposes local control over HTTP, WebSocket, and ContentProvider. Use these when the device is on the same network or connected via ADB.

## Auth token

Local HTTP and WebSocket access require a token (except `GET /ping`). You can copy it from the main screen or query it via ADB:

```bash
adb shell content query --uri content://com.droidrun.portal/auth_token
```

Response example:

```json
{"status":"success","result":"YOUR_TOKEN"}
```

## WebSocket (JSON-RPC-style)

Enable **WebSocket Server** in Settings. Default port is `8081`.

Connect with an auth token:

```bash
wscat -c "ws://localhost:8081/?token=YOUR_TOKEN"
```

Request format:

```json
{
  "id": "uuid-or-number",
  "method": "tap",
  "params": { "x": 200, "y": 400 }
}
```

Response format:

```json
{
  "id": "uuid-or-number",
  "status": "success",
  "result": "..."
}
```

### Supported methods

| Method | Params | Notes |
| --- | --- | --- |
| `tap` | `x`, `y` | Tap screen coordinates |
| `swipe` | `startX`, `startY`, `endX`, `endY`, `duration` | Duration in ms (optional) |
| `global` | `action` | Accessibility global action ID |
| `app` | `package`, `activity`, `stopBeforeLaunch` | `activity` optional; `stopBeforeLaunch` defaults to `false` |
| `app/stop` | `package` | Best-effort stop (non-privileged app) |
| `keyboard/input` | `base64_text`, `clear` | `clear` defaults to `true` |
| `keyboard/clear` | - | Clears focused input |
| `keyboard/key` | `key_code` | Uses Android key codes |
| `overlay_offset` | `offset` | Vertical offset in pixels |
| `socket_port` | `port` | Updates HTTP server port |
| `screenshot` | `hideOverlay` | Default `true` |
| `packages` | - | List launchable packages |
| `state` | `filter` | Full state; `filter=false` keeps small elements |
| `version` | - | App version |
| `time` | - | Unix ms timestamp |
| `install` | `urls`, `hideOverlay` | WebSocket only; supports split APKs |

Streaming methods (`stream/start`, `stream/stop`, `webrtc/*`) are only available over reverse connection.

Install notes:

- The device must allow **Install unknown apps** for Droidrun Portal.
- Enable **Install Auto-Accept** in Settings to auto-confirm install prompts.

### Binary screenshot responses

When `screenshot` returns binary data, the local WebSocket sends a binary frame:

- First 36 bytes: request ID string (UUID)
- Remaining bytes: PNG image bytes

If you prefer JSON, use the HTTP `/screenshot` endpoint or reverse connection (which base64-encodes the PNG).

## HTTP socket server

Enable **HTTP Server** in Settings. Default port is `8080`.

Authentication header:

```
Authorization: Bearer YOUR_TOKEN
```

Example:

```bash
curl -H "Authorization: Bearer YOUR_TOKEN" http://localhost:8080/ping
```

### GET endpoints

- `/ping` (no auth required)
- `/a11y_tree`
- `/a11y_tree_full?filter=true|false`
- `/state`
- `/state_full?filter=true|false`
- `/phone_state`
- `/version`
- `/packages`
- `/screenshot?hideOverlay=false` (binary PNG)

### POST endpoints

POST requests map to the same method names as WebSocket (e.g., `/tap`, `/action/tap`, `/keyboard/input`).

Example:

```bash
curl -X POST \
  -H "Authorization: Bearer YOUR_TOKEN" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "x=200&y=400" \
  http://localhost:8080/tap
```

## ContentProvider (ADB)

All commands use `content://com.droidrun.portal/`.

### Query

```bash
adb shell content query --uri content://com.droidrun.portal/ping
adb shell content query --uri content://com.droidrun.portal/version
adb shell content query --uri content://com.droidrun.portal/a11y_tree
adb shell content query --uri content://com.droidrun.portal/a11y_tree_full?filter=false
adb shell content query --uri content://com.droidrun.portal/phone_state
adb shell content query --uri content://com.droidrun.portal/state
adb shell content query --uri content://com.droidrun.portal/state_full?filter=false
adb shell content query --uri content://com.droidrun.portal/packages
adb shell content query --uri content://com.droidrun.portal/auth_token
```

### Insert

```bash
adb shell content insert --uri content://com.droidrun.portal/keyboard/input --bind base64_text:s:"SGVsbG8="
adb shell content insert --uri content://com.droidrun.portal/keyboard/clear
adb shell content insert --uri content://com.droidrun.portal/keyboard/key --bind key_code:i:66

adb shell content insert --uri content://com.droidrun.portal/overlay_offset --bind offset:i:100
adb shell content insert --uri content://com.droidrun.portal/overlay_visible --bind visible:b:false
adb shell content insert --uri content://com.droidrun.portal/socket_port --bind port:i:8090

adb shell content insert --uri content://com.droidrun.portal/toggle_websocket_server --bind enabled:b:true --bind port:i:8081

adb shell content insert --uri content://com.droidrun.portal/configure_reverse_connection --bind url:s:"wss://api.mobilerun.ai/v1/providers/personal/join" --bind token:s:"YOUR_TOKEN" --bind enabled:b:true
adb shell content insert --uri content://com.droidrun.portal/configure_reverse_connection --bind service_key:s:"YOUR_KEY"

adb shell content insert --uri content://com.droidrun.portal/toggle_production_mode --bind enabled:b:true
```
