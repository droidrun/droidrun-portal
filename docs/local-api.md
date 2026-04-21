# Local API

Mobilerun Portal exposes local control over HTTP, WebSocket, and ContentProvider. Use these when the device is on the same network or connected via ADB.

## Auth token

Local HTTP and WebSocket access require a token (except `GET /ping`). You can copy it from the main screen or query it via ADB:

```bash
adb shell content query --uri content://com.mobilerun.portal/auth_token
```

Response example:

```json
{"status":"success","result":"YOUR_TOKEN"}
```

## Transport matrix

| Capability | ADB ContentProvider | Local HTTP | Local WebSocket | Reverse WebSocket |
| --- | --- | --- | --- | --- |
| Tree/state/version/packages | Yes | Yes | Yes | Yes when Accessibility is ready; headless subset otherwise |
| Keyboard/overlay/config actions | Yes | Yes | Yes | Yes when Accessibility is ready |
| Screenshot | No direct endpoint | Yes | Yes | Yes when Accessibility is ready |
| APK install | No | No | Yes | Yes |
| WebRTC streaming + signaling | No | No | No | Yes |
| `files/*` | No | Yes on Android 11+ | Yes on Android 11+ | Yes on Android 11+ |

Android 8-10 support is a compatibility tier. Core control, screenshots, install flows, and reverse streaming are supported where the transport exposes them. `files/*` remains disabled below Android 11 in the current build.

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
| `files/list` | `path` | Android 11+ only in the current compatibility tier |
| `files/download` | `path` | Android 11+ only in the current compatibility tier |
| `files/upload` | `path`, `data` | Android 11+ only in the current compatibility tier |
| `files/delete` | `path` | Android 11+ only in the current compatibility tier |
| `files/fetch` | `url`, `path` | Android 11+ only in the current compatibility tier |
| `files/push` | `url`, `path` | Android 11+ only in the current compatibility tier |
| `install` | `urls`, `hideOverlay` | Local and reverse WebSocket only; supports split APKs |
| `screen/keepAwake/set` | `enabled` | WebSocket only; local and reverse; toggles the screen-awake watchdog and returns the requested target status |
| `screen/keepAwake/status` | - | WebSocket only; local and reverse; returns live watchdog status |

Streaming methods (`stream/start`, `stream/stop`, `webrtc/*`) are only available over reverse connection.
Screen-awake methods (`screen/keepAwake/*`) are available over local and reverse WebSocket. They are not available over HTTP.
Trigger management methods (`triggers/*`) are available over the local WebSocket API and reverse connection. See [Triggers and Events](triggers.md) for the exact method list, params, validation rules, and response shape.
Install is available over local and reverse WebSocket. It is not available over HTTP or the ContentProvider.
Local WebSocket sends unsolicited device events as raw
`{ "type": "...", "timestamp": ..., "payload": { ... } }` by default. Add
`?eventFormat=rpc` to request the same
`{ "method": "events/device", "params": { ... } }` envelope used by reverse connection.
`?eventFormat=legacy` is an explicit no-op for the default raw format. Raw local `PING` /
`PONG` compatibility remains unchanged. See [Reverse Connection](reverse-connection.md) for
the reverse notification shape.

Install notes:

- The device must allow **Install unknown apps** for Mobilerun Portal.
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

All commands use `content://com.mobilerun.portal/`.

The ContentProvider covers core tree/state/keyboard/overlay/config flows. It does not expose direct screenshot, install, or WebRTC streaming endpoints.

Trigger queries and mutations are also available through the same `ContentProvider`, including `triggers/catalog`, `triggers/status`, rule CRUD, enable/disable, test runs, and run history management. See [Triggers and Events](triggers.md) for the full URI list and `rule_json_base64` examples.

### Query

```bash
adb shell content query --uri content://com.mobilerun.portal/ping
adb shell content query --uri content://com.mobilerun.portal/version
adb shell content query --uri content://com.mobilerun.portal/a11y_tree
adb shell content query --uri content://com.mobilerun.portal/a11y_tree_full?filter=false
adb shell content query --uri content://com.mobilerun.portal/phone_state
adb shell content query --uri content://com.mobilerun.portal/state
adb shell content query --uri content://com.mobilerun.portal/state_full?filter=false
adb shell content query --uri content://com.mobilerun.portal/packages
adb shell content query --uri content://com.mobilerun.portal/auth_token
adb shell content query --uri content://com.mobilerun.portal/screen_keep_awake_status
```

### Insert

```bash
adb shell content insert --uri content://com.mobilerun.portal/keyboard/input --bind base64_text:s:"SGVsbG8="
adb shell content insert --uri content://com.mobilerun.portal/keyboard/clear
adb shell content insert --uri content://com.mobilerun.portal/keyboard/key --bind key_code:i:66

adb shell content insert --uri content://com.mobilerun.portal/overlay_offset --bind offset:i:100
adb shell content insert --uri content://com.mobilerun.portal/overlay_visible --bind visible:b:false
adb shell content insert --uri content://com.mobilerun.portal/socket_port --bind port:i:8090

adb shell content insert --uri content://com.mobilerun.portal/toggle_websocket_server --bind enabled:b:true --bind port:i:8081
adb shell content insert --uri content://com.mobilerun.portal/toggle_screen_keep_awake --bind enabled:b:true
adb shell content insert --uri content://com.mobilerun.portal/toggle_screen_keep_awake --bind enabled:b:false

adb shell content insert --uri content://com.mobilerun.portal/configure_reverse_connection --bind url_base64:s:"d3NzOi8vYXBpLm1vYmlsZXJ1bi5haS92MS9wcm92aWRlcnMvcGVyc29uYWwvam9pbg==" --bind token_base64:s:"WU9VUl9UT0tFTg==" --bind enabled:b:true
adb shell content insert --uri content://com.mobilerun.portal/configure_reverse_connection --bind service_key_base64:s:"WU9VUl9LRVk="

adb shell content insert --uri content://com.mobilerun.portal/toggle_production_mode --bind enabled:b:true
```
