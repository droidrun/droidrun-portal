package com.mobilerun.portal.events.model

import org.json.JSONObject

data class PortalEvent(
    val type: EventType,
    val timestamp: Long = System.currentTimeMillis(),
    val payload: Any? = null
) {
    fun toJsonObject(): JSONObject {
        return JSONObject().apply {
            put("type", type.name)
            put("timestamp", timestamp)

            when (payload) {
                is Map<*, *> -> put("payload", JSONObject(payload))
                is JSONObject -> put("payload", payload)
                is String -> put("payload", payload)
                null -> Unit
                else -> put("payload", payload.toString())
            }
        }
    }

    fun toJson(): String {
        return toJsonObject().toString()
    }

    fun toReverseNotificationJson(): String {
        return JSONObject().apply {
            put("method", "events/device")
            put("params", toJsonObject())
        }.toString()
    }
    
    companion object {
        fun fromJson(jsonStr: String): PortalEvent {
            try {
                val json = JSONObject(jsonStr)
                val typeStr = json.optString("type", "UNKNOWN")
                val type = try {
                    EventType.valueOf(typeStr)
                } catch (e: IllegalArgumentException) {
                    EventType.UNKNOWN
                }
                
                val timestamp = json.optLong("timestamp", System.currentTimeMillis())
                
                // We keep payload as raw object or try to parse if complex
                val payloadOpt = json.opt("payload")
                
                return PortalEvent(type, timestamp, payloadOpt)
            } catch (e: Exception) {
                return PortalEvent(EventType.UNKNOWN, payload = "Parse Error: ${e.message}")
            }
        }
    }
}
