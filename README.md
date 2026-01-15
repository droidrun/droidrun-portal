<picture>
  <source media="(prefers-color-scheme: dark)" srcset="./static/droidrun-dark.png">
  <source media="(prefers-color-scheme: light)" srcset="./static/droidrun.png">
  <img src="./static/droidrun.png"  width="full">
</picture>

[![GitHub stars](https://img.shields.io/github/stars/droidrun/droidrun-portal?style=social)](https://github.com/droidrun/droidrun-portal/stargazers)
[![Discord](https://img.shields.io/discord/1360219330318696488?color=7289DA&label=Discord&logo=discord&logoColor=white)](https://discord.gg/ZZbKEZZkwK)
[![Documentation](https://img.shields.io/badge/Documentation-📕-blue)](https://docs.droidrun.ai)
[![Twitter Follow](https://img.shields.io/twitter/follow/droid_run?style=social)](https://x.com/droid_run)

<a href="https://github.com/droidrun/droidrun-portal/releases" target="_blank">
    <img src="https://raw.githubusercontent.com/Kunzisoft/Github-badge/main/get-it-on-github.png" alt="Get it on GitHub" style="width:200px;height:auto;">
</a>

## 👁️ Overview
Droidrun Portal is an Android accessibility service that provides real-time visual feedback and data collection for UI elements on the screen. It creates an interactive overlay that highlights clickable, checkable, editable, scrollable, and focusable elements, making it an invaluable tool for UI testing, automation development, and accessibility assessment.

## ✨ Features

- Interactive overlay that highlights clickable, checkable, editable, scrollable, and focusable elements
- Local control APIs (HTTP socket server, WebSocket JSON-RPC, and ContentProvider)
- Reverse WebSocket connection for cloud control
- WebRTC screen streaming with auto-accept support
- APK install from URLs (including split APKs) with optional auto-accept
- Notification event streaming with per-event toggles

## 🚀 Usage

### ⚙️ Setup
1. Install the app on your Android device
2. Enable the accessibility service in Android Settings → Accessibility → Droidrun Portal
3. Grant overlay permission when prompted
4. (Optional) Open **Settings** in the app to enable local servers or reverse connection

### 🔐 Auth Token (Local APIs)

Droidrun Portal generates a local auth token for HTTP and WebSocket access.

- In the app: copy the token from the main screen
- Via ADB:
  ```bash
  adb shell content query --uri content://com.droidrun.portal/auth_token
  ```

### 🧩 Local APIs

Droidrun Portal exposes three local interfaces:

- HTTP socket server (default port 8080)
- WebSocket server (default port 8081)
- ContentProvider (ADB commands)

See [Local API](docs/local-api.md) for full details and examples.

### 📡 WebSocket Events

Droidrun Portal streams notification events over WebSocket when enabled in Settings.

See the [WebSocket Events documentation](docs/websocket-events.md) for setup, permissions, and event formats.

### 🌐 Reverse Connection (Cloud)

Enable reverse connection to let the device initiate an outbound WebSocket connection to a host (used by Mobilerun Cloud).

See [Reverse Connection](docs/reverse-connection.md) for configuration details and the streaming protocol.

### 💻 ADB Commands (ContentProvider)

All commands use the ContentProvider authority `content://com.droidrun.portal/`.

#### Query Commands (Reading Data)

```bash
# Test connection (ping)
adb shell content query --uri content://com.droidrun.portal/ping

# Get app version
adb shell content query --uri content://com.droidrun.portal/version

# Get accessibility tree as JSON (visible elements with overlay indices)
adb shell content query --uri content://com.droidrun.portal/a11y_tree

# Get full accessibility tree with ALL properties (complete node info)
adb shell content query --uri content://com.droidrun.portal/a11y_tree_full

# Get full tree without filtering small elements (< 1% visibility)
adb shell content query --uri 'content://com.droidrun.portal/a11y_tree_full?filter=false'

# Get phone state as JSON (current app, focused element, keyboard visibility)
adb shell content query --uri content://com.droidrun.portal/phone_state

# Get combined state (accessibility tree + phone state)
adb shell content query --uri content://com.droidrun.portal/state

# Get full combined state (full tree + phone state + device context)
adb shell content query --uri content://com.droidrun.portal/state_full

# Get full state without filtering
adb shell content query --uri 'content://com.droidrun.portal/state_full?filter=false'

# Get list of installed launchable apps
adb shell content query --uri content://com.droidrun.portal/packages

# Get local auth token for HTTP/WS access
adb shell content query --uri content://com.droidrun.portal/auth_token
```

#### Insert Commands (Actions & Configuration)

```bash
# Keyboard text input (base64 encoded, clears field first by default)
adb shell content insert --uri content://com.droidrun.portal/keyboard/input --bind base64_text:s:"SGVsbG8gV29ybGQ="

# Keyboard text input without clearing the field first
adb shell content insert --uri content://com.droidrun.portal/keyboard/input --bind base64_text:s:"SGVsbG8=" --bind clear:b:false

# Clear text in focused input field
adb shell content insert --uri content://com.droidrun.portal/keyboard/clear

# Send key event via keyboard (e.g., Enter key = 66, Backspace = 67)
adb shell content insert --uri content://com.droidrun.portal/keyboard/key --bind key_code:i:66

# Set overlay vertical offset (in pixels)
adb shell content insert --uri content://com.droidrun.portal/overlay_offset --bind offset:i:100

# Toggle overlay visibility (show/hide)
adb shell content insert --uri content://com.droidrun.portal/overlay_visible --bind visible:b:true
adb shell content insert --uri content://com.droidrun.portal/overlay_visible --bind visible:b:false

# Configure REST API socket server port (default: 8080)
adb shell content insert --uri content://com.droidrun.portal/socket_port --bind port:i:8090

# Enable/disable local WebSocket server (default port: 8081)
adb shell content insert --uri content://com.droidrun.portal/toggle_websocket_server --bind enabled:b:true --bind port:i:8081

# Configure reverse connection (host URL + optional token/service key)
adb shell content insert --uri content://com.droidrun.portal/configure_reverse_connection --bind url:s:"wss://api.mobilerun.ai/v1/devices/{deviceId}/join" --bind token:s:"YOUR_TOKEN" --bind enabled:b:true
adb shell content insert --uri content://com.droidrun.portal/configure_reverse_connection --bind service_key:s:"YOUR_KEY"

# Toggle production mode UI
adb shell content insert --uri content://com.droidrun.portal/toggle_production_mode --bind enabled:b:true
```

#### Common Key Codes

| Key | Code | Key | Code |
|-----|------|-----|------|
| Enter | 66 | Backspace | 67 |
| Tab | 61 | Escape | 111 |
| Home | 3 | Back | 4 |
| Up | 19 | Down | 20 |
| Left | 21 | Right | 22 |

### 📤 Data Output
Element data is returned in JSON format through the ContentProvider queries. The response includes a status field and the requested data. All responses follow this structure:

```json
{
  "status": "success",
  "result": "..."
}
```

For error responses:
```json
{
  "status": "error", 
  "error": "Error message"
}
```

## 🔧 Technical Details
- Minimum Android API level: 30 (Android 11.0)
- Uses Android Accessibility Service API
- Implements custom drawing overlay using Window Manager
- Supports multi-window environments
- Built with Kotlin


## 🔄 Continuous Integration

This project uses GitHub Actions for automated building and releasing.

### 📦 Automated Builds

Every push to the main branch or pull request will trigger the build workflow that:
- Builds the Android app
- Creates the APK
- Uploads the APK as an artifact in the GitHub Actions run
