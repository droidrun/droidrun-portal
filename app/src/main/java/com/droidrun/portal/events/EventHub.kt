package com.droidrun.portal.events

import android.util.Log
import com.droidrun.portal.config.ConfigManager
import com.droidrun.portal.events.model.EventType
import com.droidrun.portal.events.model.PortalEvent

object EventHub {
    private const val TAG = "DroidrunEventHub"

    // Single listener (The WebSocket Server)
    private var serverListener: ((PortalEvent) -> Unit)? = null

    // TODO replace
    private var configManager: ConfigManager? = null

    fun init(config: ConfigManager) {
        this.configManager = config
    }

    fun subscribe(callback: (PortalEvent) -> Unit) {
        serverListener = callback
    }

    fun emit(event: PortalEvent) {
        // Check if this specific event type is enabled in config
        if (isEventEnabled(event.type)) {
            try {
                serverListener?.invoke(event)
            } catch (e: Exception) {
                Log.e(TAG, "Error broadcasting event: ${e.message}")
            }
        }
    }

    private fun isEventEnabled(type: EventType): Boolean {
        val config = configManager ?: return true // Default to true if config not loaded

        // Always allow PONG and UNKNOWN (debug)
        if (type == EventType.PONG || type == EventType.UNKNOWN) return true

        // Check ConfigManager for specific toggle
        // We'll implement this dynamic check in ConfigManager next
        return config.isEventEnabled(type)
    }
}