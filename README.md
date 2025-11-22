<picture>
  <source media="(prefers-color-scheme: dark)" srcset="./static/droidrun-dark.png">
  <source media="(prefers-color-scheme: light)" srcset="./static/droidrun.png">
  <img src="./static/droidrun.png"  width="full">
</picture>

[![GitHub stars](https://img.shields.io/github/stars/droidrun/droidrun-portal?style=social)](https://github.com/droidrun/droidrun-portal/stargazers)
[![Discord](https://img.shields.io/discord/1360219330318696488?color=7289DA&label=Discord&logo=discord&logoColor=white)](https://discord.gg/ZZbKEZZkwK)
[![Documentation](https://img.shields.io/badge/Documentation-📕-blue)](https://docs.droidrun.ai)
[![Twitter Follow](https://img.shields.io/twitter/follow/droid_run?style=social)](https://x.com/droid_run)

## 👁️ Overview
Droidrun Portal is an Android accessibility service that provides real-time visual feedback and data collection for UI elements on the screen. It creates an interactive overlay that highlights clickable, checkable, editable, scrollable, and focusable elements, making it an invaluable tool for UI testing, automation development, and accessibility assessment.

## ✨ Features

### 🔍 Element Detection with Visual Overlay
- Identifies all interactive elements (clickable, checkable, editable, scrollable, and focusable)
- Handles nested elements and scrollable containers
- Assigns unique indices to interactive elements for reference

## 🚀 Usage

### ⚙️ Setup
1. Install the app on your Android device
2. Enable the accessibility service in Android Settings → Accessibility → Droidrun Portal
3. Grant overlay permission when prompted

### 📡 WebSocket Events

Droidrun Portal includes a WebSocket server for real-time event streaming (notifications, etc.).

See the [WebSocket Events documentation](docs/websocket-events.md) for setup and usage.

### 💻 ADB Commands
```bash
# Get accessibility tree as JSON
adb shell content query --uri content://com.droidrun.portal/a11y_tree

# Get phone state as JSON
adb shell content query --uri content://com.droidrun.portal/phone_state

# Get combined state (accessibility tree + phone state) as JSON
adb shell content query --uri content://com.droidrun.portal/state

# Test connection (ping)
adb shell content query --uri content://com.droidrun.portal/ping

# Keyboard text input (base64 encoded)
adb shell content insert --uri content://com.droidrun.portal/keyboard/input --bind base64_text:s:"SGVsbG8gV29ybGQ="

# Clear text via keyboard
adb shell content insert --uri content://com.droidrun.portal/keyboard/clear

# Send key event via keyboard (e.g., Enter key = 66)
adb shell content insert --uri content://com.droidrun.portal/keyboard/key --bind key_code:i:66
```

### 📤 Data Output
Element data is returned in JSON format through the ContentProvider queries. The response includes a status field and the requested data. All responses follow this structure:

```json
{
  "status": "success",
  "data": "..."
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
