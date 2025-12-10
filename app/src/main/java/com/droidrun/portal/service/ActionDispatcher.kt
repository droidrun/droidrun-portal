package com.droidrun.portal.service

import com.droidrun.portal.api.ApiHandler
import com.droidrun.portal.api.ApiResponse
import org.json.JSONObject

/**
 * Dispatches actions (tap, swipe, etc.) to the appropriate handler.
 * Used by both HTTP (SocketServer) and WebSocket (PortalWebSocketServer) layers
 * to ensure consistent behavior and avoid code duplication.
 */
class ActionDispatcher(private val apiHandler: ApiHandler) {

    /**
     * Dispatch a command based on the action name and parameters.
     *
     * @param action The action/endpoint name (e.g. "tap", "swipe", "/action/tap")
     * @param params The JSON parameters for the action
     * @return ApiResponse result
     */
    fun dispatch(action: String, params: JSONObject): ApiResponse {
        // Normalize action name (handle both "action.tap" and "/action/tap" styles)
        val method = action.removePrefix("/action/").removePrefix("action.")

        return when (method) {
            "tap" -> {
                val x = params.optInt("x", 0)
                val y = params.optInt("y", 0)
                apiHandler.performTap(x, y)
            }
            "swipe" -> {
                val startX = params.optInt("startX", 0)
                val startY = params.optInt("startY", 0)
                val endX = params.optInt("endX", 0)
                val endY = params.optInt("endY", 0)
                val duration = params.optInt("duration", 300)
                apiHandler.performSwipe(startX, startY, endX, endY, duration)
            }
            "global" -> {
                val actionId = params.optInt("action", 0)
                apiHandler.performGlobalAction(actionId)
            }
            "app" -> {
                val pkg = params.optString("package", "")
                val activity = params.optString("activity", null)
                // JSON optString returns "" for missing keys if default not null? 
                // Let's be safe: treat empty string or "null" literal as null
                val finalActivity = if (activity.isNullOrEmpty() || activity == "null") null else activity
                apiHandler.startApp(pkg, finalActivity)
            }
            "keyboard/input", "input" -> {
                val text = params.optString("base64_text", "")
                val clear = params.optBoolean("clear", true)
                apiHandler.keyboardInput(text, clear)
            }
            "keyboard/clear", "clear" -> {
                apiHandler.keyboardClear()
            }
            "keyboard/key", "key" -> {
                val keyCode = params.optInt("key_code", 0)
                apiHandler.keyboardKey(keyCode)
            }
            "overlay_offset" -> {
                val offset = params.optInt("offset", 0)
                apiHandler.setOverlayOffset(offset)
            }
            "socket_port" -> {
                val port = params.optInt("port", 0)
                apiHandler.setSocketPort(port)
            }
            "screenshot" -> {
                // Default to hiding overlay unless specified otherwise
                val hideOverlay = params.optBoolean("hideOverlay", true)
                apiHandler.getScreenshot(hideOverlay)
            }
            else -> ApiResponse.Error("Unknown method: $method")
        }
    }
}
