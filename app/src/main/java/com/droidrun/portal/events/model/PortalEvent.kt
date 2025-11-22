package com.droidrun.portal.events.model

import org.json.JSONObject

data class PortalEvent(
    val type: EventType,
    val timestamp: Long = System.currentTimeMillis(),
    val payload: Any? = null
) {
    fun toJson(): String {
        val json = JSONObject()
        json.put("type", type.name)
        json.put("timestamp", timestamp)
        
        when (payload) {
            is Map<*, *> -> json.put("payload", JSONObject(payload))
            is JSONObject -> json.put("payload", payload)
            is String -> json.put("payload", payload)
            null -> {} // No payload
            else -> json.put("payload", payload.toString())
        }
        
        return json.toString()
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
