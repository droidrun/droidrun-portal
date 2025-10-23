package com.droidrun.portal

import android.content.Context
import android.content.SharedPreferences

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
        private const val DEFAULT_OFFSET = 0
        private const val DEFAULT_SOCKET_PORT = 8080
        
        @Volatile
        private var INSTANCE: ConfigManager? = null
        
        fun getInstance(context: Context): ConfigManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ConfigManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    private val sharedPrefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    // Overlay visibility
    var overlayVisible: Boolean
        get() = sharedPrefs.getBoolean(KEY_OVERLAY_VISIBLE, true)
        set(value) {
            sharedPrefs.edit().putBoolean(KEY_OVERLAY_VISIBLE, value).apply()
        }
    
    // Overlay offset
    var overlayOffset: Int
        get() = sharedPrefs.getInt(KEY_OVERLAY_OFFSET, DEFAULT_OFFSET)
        set(value) {
            sharedPrefs.edit().putInt(KEY_OVERLAY_OFFSET, value).apply()
        }

    // Auto offset enabled
    var autoOffsetEnabled: Boolean
        get() = sharedPrefs.getBoolean(KEY_AUTO_OFFSET_ENABLED, true)
        set(value) {
            sharedPrefs.edit().putBoolean(KEY_AUTO_OFFSET_ENABLED, value).apply()
        }

    // Track if auto offset has been calculated before
    var autoOffsetCalculated: Boolean
        get() = sharedPrefs.getBoolean(KEY_AUTO_OFFSET_CALCULATED, false)
        set(value) {
            sharedPrefs.edit().putBoolean(KEY_AUTO_OFFSET_CALCULATED, value).apply()
        }

    // Socket server enabled
    var socketServerEnabled: Boolean
        get() = sharedPrefs.getBoolean(KEY_SOCKET_SERVER_ENABLED, true)
        set(value) {
            sharedPrefs.edit().putBoolean(KEY_SOCKET_SERVER_ENABLED, value).apply()
        }
    
    // Socket server port
    var socketServerPort: Int
        get() = sharedPrefs.getInt(KEY_SOCKET_SERVER_PORT, DEFAULT_SOCKET_PORT)
        set(value) {
            sharedPrefs.edit().putInt(KEY_SOCKET_SERVER_PORT, value).apply()
        }
    
    // Listener interface for configuration changes
    interface ConfigChangeListener {
        fun onOverlayVisibilityChanged(visible: Boolean)
        fun onOverlayOffsetChanged(offset: Int)
        fun onSocketServerEnabledChanged(enabled: Boolean)
        fun onSocketServerPortChanged(port: Int)
    }
    
    private val listeners = mutableSetOf<ConfigChangeListener>()
    
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
    
    // Bulk configuration update
    fun updateConfiguration(
        overlayVisible: Boolean? = null,
        overlayOffset: Int? = null,
        autoOffsetEnabled: Boolean? = null,
        socketServerEnabled: Boolean? = null,
        socketServerPort: Int? = null
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
        
        if (hasChanges) {
            editor.apply()
            
            // Notify listeners
            overlayVisible?.let { listeners.forEach { listener -> listener.onOverlayVisibilityChanged(it) } }
            overlayOffset?.let { listeners.forEach { listener -> listener.onOverlayOffsetChanged(it) } }
            socketServerEnabled?.let { listeners.forEach { listener -> listener.onSocketServerEnabledChanged(it) } }
            socketServerPort?.let { listeners.forEach { listener -> listener.onSocketServerPortChanged(it) } }
        }
    }
    
    // Get all configuration as a data class
    data class Configuration(
        val overlayVisible: Boolean,
        val overlayOffset: Int,
        val autoOffsetEnabled: Boolean,
        val autoOffsetCalculated: Boolean,
        val socketServerEnabled: Boolean,
        val socketServerPort: Int
    )

    fun getCurrentConfiguration(): Configuration {
        return Configuration(
            overlayVisible = overlayVisible,
            overlayOffset = overlayOffset,
            autoOffsetEnabled = autoOffsetEnabled,
            autoOffsetCalculated = autoOffsetCalculated,
            socketServerEnabled = socketServerEnabled,
            socketServerPort = socketServerPort
        )
    }
} 