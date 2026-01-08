package com.droidrun.portal.config

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.core.content.edit
import com.droidrun.portal.events.model.EventType

/**
 * Centralized configuration manager for Droidrun Portal
 * Handles SharedPreferences operations and provides a clean API for configuration management
 */
class ConfigManager private constructor(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "droidrun_config"
        private const val KEY_OVERLAY_VISIBLE = "overlay_visible"
        private const val KEY_OVERLAY_OFFSET = "overlay_offset"
        private const val KEY_AUTO_OFFSET_ENABLED = "auto_offset_enabled"
        private const val KEY_AUTO_OFFSET_CALCULATED = "auto_offset_calculated"
        private const val KEY_SOCKET_SERVER_ENABLED = "socket_server_enabled"
        private const val KEY_SOCKET_SERVER_PORT = "socket_server_port"

        // WebSocket & Events
        private const val KEY_WEBSOCKET_ENABLED = "websocket_enabled"
        private const val KEY_WEBSOCKET_PORT = "websocket_port"
        private const val KEY_REVERSE_CONNECTION_URL = "reverse_connection_url"
        private const val KEY_REVERSE_CONNECTION_TOKEN = "reverse_connection_token"
        private const val KEY_REVERSE_CONNECTION_ENABLED = "reverse_connection_enabled"
        private const val KEY_REVERSE_CONNECTION_SERVICE_KEY = "reverse_connection_service_key"
        private const val PREFIX_EVENT_ENABLED = "event_enabled_"
        private const val KEY_AUTH_TOKEN = "auth_token"
        private const val KEY_DEVICE_ID = "device_id"
        private const val DEVICE_ID_PLACEHOLDER = "{deviceId}"

        private const val DEFAULT_OFFSET = 0
        private const val DEFAULT_SOCKET_PORT = 8080
        private const val DEFAULT_WEBSOCKET_PORT = 8081
        private const val DEFAULT_REVERSE_CONNECTION_URL =
            "wss://api.mobilerun.ai/v1/devices/{deviceId}/join"

        // TODO replace
        @Volatile
        private var INSTANCE: ConfigManager? = null

        fun getInstance(context: Context): ConfigManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ConfigManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val sharedPrefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        if (sharedPrefs.contains(KEY_REVERSE_CONNECTION_ENABLED)) {
            sharedPrefs.edit { putBoolean(KEY_REVERSE_CONNECTION_ENABLED, false) }
        }
    }

    // Auth Token (Auto-generated if missing)
    // TODO add external injection from some config file
    val authToken: String
        get() {
            var token = sharedPrefs.getString(KEY_AUTH_TOKEN, null)
            if (token == null) {
                token = java.util.UUID.randomUUID().toString()
                sharedPrefs.edit { putString(KEY_AUTH_TOKEN, token) }
            }
            return token
        }

    val deviceID: String
        get() {
            var id = sharedPrefs.getString(KEY_DEVICE_ID, null)
            if (id == null) {
                id = java.util.UUID.randomUUID().toString()
                sharedPrefs.edit { putString(KEY_DEVICE_ID, id) }
            }
            return id
        }

    // Overlay visibility
    var overlayVisible: Boolean
        get() = sharedPrefs.getBoolean(KEY_OVERLAY_VISIBLE, true)
        set(value) {
            sharedPrefs.edit { putBoolean(KEY_OVERLAY_VISIBLE, value) }
        }

    // Overlay offset
    var overlayOffset: Int
        get() = sharedPrefs.getInt(KEY_OVERLAY_OFFSET, DEFAULT_OFFSET)
        set(value) {
            sharedPrefs.edit { putInt(KEY_OVERLAY_OFFSET, value) }
        }

    // Auto offset enabled
    var autoOffsetEnabled: Boolean
        get() = sharedPrefs.getBoolean(KEY_AUTO_OFFSET_ENABLED, true)
        set(value) {
            sharedPrefs.edit { putBoolean(KEY_AUTO_OFFSET_ENABLED, value) }
        }

    // Track if auto offset has been calculated before
    var autoOffsetCalculated: Boolean
        get() = sharedPrefs.getBoolean(KEY_AUTO_OFFSET_CALCULATED, false)
        set(value) {
            sharedPrefs.edit { putBoolean(KEY_AUTO_OFFSET_CALCULATED, value) }
        }

    // Socket server enabled (REST API)
    var socketServerEnabled: Boolean
        get() = sharedPrefs.getBoolean(KEY_SOCKET_SERVER_ENABLED, false)
        set(value) {
            sharedPrefs.edit { putBoolean(KEY_SOCKET_SERVER_ENABLED, value) }
        }

    // Socket server port (REST API)
    var socketServerPort: Int
        get() = sharedPrefs.getInt(KEY_SOCKET_SERVER_PORT, DEFAULT_SOCKET_PORT)
        set(value) {
            sharedPrefs.edit { putInt(KEY_SOCKET_SERVER_PORT, value) }
        }

    // WebSocket Server Enabled
    var websocketEnabled: Boolean
        get() = sharedPrefs.getBoolean(KEY_WEBSOCKET_ENABLED, true)
        set(value) {
            sharedPrefs.edit { putBoolean(KEY_WEBSOCKET_ENABLED, value) }
        }

    // WebSocket Server Port
    var websocketPort: Int
        get() = sharedPrefs.getInt(KEY_WEBSOCKET_PORT, DEFAULT_WEBSOCKET_PORT)
        set(value) {
            sharedPrefs.edit().putInt(KEY_WEBSOCKET_PORT, value).apply()
        }

    // Reverse Connection URL
    var reverseConnectionUrl: String
        get() = sharedPrefs.getString(KEY_REVERSE_CONNECTION_URL, "") ?: ""
        set(value) {
            sharedPrefs.edit { putString(KEY_REVERSE_CONNECTION_URL, value) }
        }

    val reverseConnectionUrlOrDefault: String
        get() {
            val stored = reverseConnectionUrl
            return stored.ifBlank { DEFAULT_REVERSE_CONNECTION_URL }
        }

    val reverseConnectionUrlForDisplay: String
        get() = reverseConnectionUrlOrDefault.replace(DEVICE_ID_PLACEHOLDER, deviceID)

    // Reverse Connection Token (Optional, for authenticating with Host/Cloud)
    var reverseConnectionToken: String
        get() = sharedPrefs.getString(KEY_REVERSE_CONNECTION_TOKEN, "") ?: ""
        set(value) {
            sharedPrefs.edit { putString(KEY_REVERSE_CONNECTION_TOKEN, value) }
        }

    // Reverse Connection Service Key (Header: X-Remote-Device-Key)
    var reverseConnectionServiceKey: String
        get() = sharedPrefs.getString(KEY_REVERSE_CONNECTION_SERVICE_KEY, "") ?: ""
        set(value) {
            sharedPrefs.edit { putString(KEY_REVERSE_CONNECTION_SERVICE_KEY, value) }
        }

    val deviceName: String
        get() {
            val manufacturer = Build.MANUFACTURER
            val model = Build.MODEL

            return if (model.startsWith(manufacturer)) {
                capitalize(model)
            } else {
                capitalize(manufacturer) + " " + model
            }
        }

    val deviceCountryCode: String
        get() {
            // TODO:
            return "de"
        }

    val userID: String
        get() {
            return "7785b089-b9aa-458d-a32e-baec315e5e16"
        }

    var reverseConnectionEnabled: Boolean = false

    var screenShareAutoAcceptEnabled: Boolean
        get() = sharedPrefs.getBoolean("screen_share_auto_accept_enabled", true)
        set(value) {
            sharedPrefs.edit { putBoolean("screen_share_auto_accept_enabled", value) }
        }

    // Listener interface for configuration changes
    interface ConfigChangeListener {
        fun onOverlayVisibilityChanged(visible: Boolean)
        fun onOverlayOffsetChanged(offset: Int)
        fun onSocketServerEnabledChanged(enabled: Boolean)
        fun onSocketServerPortChanged(port: Int)

        // New WebSocket listeners
        fun onWebSocketEnabledChanged(enabled: Boolean) {}
        fun onWebSocketPortChanged(port: Int) {}
    }

    private val listeners = mutableSetOf<ConfigChangeListener>()

    fun capitalize(str: String): String {
        return str.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }

    fun normalizeReverseConnectionUrlForStorage(input: String): String {
        if (input.isBlank()) return ""
        return input.replace(deviceID, DEVICE_ID_PLACEHOLDER)
    }

    // Dynamic Event Toggles
    fun isEventEnabled(type: EventType): Boolean {
        // Default all events to true unless explicitly disabled
        return sharedPrefs.getBoolean(PREFIX_EVENT_ENABLED + type.name, true)
    }

    fun setEventEnabled(type: EventType, enabled: Boolean) {
        sharedPrefs.edit { putBoolean(PREFIX_EVENT_ENABLED + type.name, enabled) }
        // We could notify listeners here if needed, but usually this is polled by EventHub
    }

    fun addListener(listener: ConfigChangeListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: ConfigChangeListener) {
        listeners.remove(listener)
    }

    fun setOverlayVisibleWithNotification(visible: Boolean) {
        overlayVisible = visible
        listeners.forEach { it.onOverlayVisibilityChanged(visible) }
    }

    fun setOverlayOffsetWithNotification(offset: Int) {
        overlayOffset = offset
        listeners.forEach { it.onOverlayOffsetChanged(offset) }
    }

    fun setSocketServerEnabledWithNotification(enabled: Boolean) {
        socketServerEnabled = enabled
        listeners.forEach { it.onSocketServerEnabledChanged(enabled) }
    }

    fun setSocketServerPortWithNotification(port: Int) {
        socketServerPort = port
        listeners.forEach { it.onSocketServerPortChanged(port) }
    }

    fun setWebSocketEnabledWithNotification(enabled: Boolean) {
        websocketEnabled = enabled
        listeners.forEach { it.onWebSocketEnabledChanged(enabled) }
    }

    fun setWebSocketPortWithNotification(port: Int) {
        websocketPort = port
        listeners.forEach { it.onWebSocketPortChanged(port) }
    }

    // Bulk configuration update
    fun updateConfiguration(
        overlayVisible: Boolean? = null,
        overlayOffset: Int? = null,
        autoOffsetEnabled: Boolean? = null,
        socketServerEnabled: Boolean? = null,
        socketServerPort: Int? = null,
        websocketEnabled: Boolean? = null,
        websocketPort: Int? = null
    ) {
        val editor = sharedPrefs.edit()
        var hasChanges = false

        overlayVisible?.let {
            editor.putBoolean(KEY_OVERLAY_VISIBLE, it)
            hasChanges = true
        }

        overlayOffset?.let {
            editor.putInt(KEY_OVERLAY_OFFSET, it)
            hasChanges = true
        }

        autoOffsetEnabled?.let {
            editor.putBoolean(KEY_AUTO_OFFSET_ENABLED, it)
            hasChanges = true
        }

        socketServerEnabled?.let {
            editor.putBoolean(KEY_SOCKET_SERVER_ENABLED, it)
            hasChanges = true
        }

        socketServerPort?.let {
            editor.putInt(KEY_SOCKET_SERVER_PORT, it)
            hasChanges = true
        }

        websocketEnabled?.let {
            editor.putBoolean(KEY_WEBSOCKET_ENABLED, it)
            hasChanges = true
        }

        websocketPort?.let {
            editor.putInt(KEY_WEBSOCKET_PORT, it)
            hasChanges = true
        }

        if (hasChanges) {
            editor.apply()

            // Notify listeners
            overlayVisible?.let {
                listeners.forEach { listener ->
                    listener.onOverlayVisibilityChanged(
                        it
                    )
                }
            }
            overlayOffset?.let { listeners.forEach { listener -> listener.onOverlayOffsetChanged(it) } }
            socketServerEnabled?.let {
                listeners.forEach { listener ->
                    listener.onSocketServerEnabledChanged(
                        it
                    )
                }
            }
            socketServerPort?.let {
                listeners.forEach { listener ->
                    listener.onSocketServerPortChanged(
                        it
                    )
                }
            }
            websocketEnabled?.let {
                listeners.forEach { listener ->
                    listener.onWebSocketEnabledChanged(
                        it
                    )
                }
            }
            websocketPort?.let { listeners.forEach { listener -> listener.onWebSocketPortChanged(it) } }
        }
    }

    // Get all configuration as a data class
    data class Configuration(
        val overlayVisible: Boolean,
        val overlayOffset: Int,
        val autoOffsetEnabled: Boolean,
        val autoOffsetCalculated: Boolean,
        val socketServerEnabled: Boolean,
        val socketServerPort: Int,
        val websocketEnabled: Boolean,
        val websocketPort: Int,
        val authToken: String
    )

    fun getCurrentConfiguration(): Configuration {
        return Configuration(
            overlayVisible = overlayVisible,
            overlayOffset = overlayOffset,
            autoOffsetEnabled = autoOffsetEnabled,
            autoOffsetCalculated = autoOffsetCalculated,
            socketServerEnabled = socketServerEnabled,
            socketServerPort = socketServerPort,
            websocketEnabled = websocketEnabled,
            websocketPort = websocketPort,
            authToken = authToken
        )
    }
}
