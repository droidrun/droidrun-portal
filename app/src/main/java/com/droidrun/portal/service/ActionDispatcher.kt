package com.droidrun.portal.service

import com.droidrun.portal.api.ApiHandler
import com.droidrun.portal.api.ApiResponse
import org.json.JSONArray
import org.json.JSONObject

/**
 * Dispatches actions (tap, swipe, etc.) to the appropriate handler.
 * Used by both HTTP (SocketServer) and WebSocket (PortalWebSocketServer) layers
 * to ensure consistent behavior and avoid code duplication.
 */
class ActionDispatcher(private val apiHandler: ApiHandler) {

    companion object {
        private const val DEFAULT_SWIPE_DURATION_MS = 300
    }

    enum class Origin {
        HTTP,
        WEBSOCKET_LOCAL,
        WEBSOCKET_REVERSE,
    }

    /**
     * Dispatch a command based on the action name and parameters.
     *
     * @param action The action/endpoint name (e.g. "tap", "swipe", "/action/tap")
     * @param params The JSON parameters for the action
     * @return ApiResponse result
     */
    fun dispatch(
        action: String,
        params: JSONObject,
        origin: Origin = Origin.WEBSOCKET_LOCAL,
        requestId: Any? = null,
    ): ApiResponse {
        // Normalize action name (handle both "action.tap" and "/action/tap" styles)
        return when (
            val method =
                action.removePrefix("/action/").removePrefix("action.").removePrefix("/")
        ) {
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
                val duration = params.optInt("duration", DEFAULT_SWIPE_DURATION_MS)
                apiHandler.performSwipe(startX, startY, endX, endY, duration)
            }

            "global" -> {
                val actionId = params.optInt("action", 0)
                apiHandler.performGlobalAction(actionId)
            }

            "app" -> {
                val pkg = params.optString("package", "")
                val activity = params.optString("activity", "")
                // JSON optString returns "" for missing keys
                // Let's be safe: treat empty string or "null" literal as null
                val finalActivity =
                    if (activity.isNullOrEmpty() || activity == "null") null else activity
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

            "packages" -> {
                apiHandler.getPackages()
            }

            "state" -> {
                val filter = params.optBoolean("filter", false)
                apiHandler.getStateFull(filter)
            }

            "version" -> {
                apiHandler.getVersion()
            }

            "time" -> {
                apiHandler.getTime()
            }

            "install" -> {
                if (origin == Origin.HTTP)
                    return ApiResponse.Error("Install is only supported over WebSocket")

                val hideOverlay = params.optBoolean("hideOverlay", false)

                val urlsArray: JSONArray? = params.optJSONArray("urls")
                if (urlsArray == null || urlsArray.length() == 0)
                    return ApiResponse.Error("Missing required param: 'urls'")

                val urls = mutableListOf<String>()
                for (i in 0 until urlsArray.length()) {
                    val url = urlsArray.optString(i, "").trim()
                    if (url.isNotEmpty()) urls.add(url)
                }

                if (urls.isEmpty()) {
                    ApiResponse.Error("Missing required param: 'urls'")
                } else {
                    apiHandler.installFromUrls(urls, hideOverlay)
                }
            }

            // Streaming Commands (websocket required)
            "stream/start" -> {
                if (origin != Origin.WEBSOCKET_REVERSE) {
                    ApiResponse.Error("Streaming commands require reverse WebSocket connection")
                } else {
                    apiHandler.startStream(params, requestId)
                }
            }

            "stream/stop" -> {
                if (origin == Origin.HTTP) {
                    ApiResponse.Error("Streaming commands require WebSocket connection")
                } else {
                    apiHandler.stopStream()
                }
            }

            "webrtc/answer" -> {
                if (origin != Origin.WEBSOCKET_REVERSE) {
                    ApiResponse.Error("WebRTC signaling requires reverse WebSocket connection")
                } else {
                    val sdp = params.getString("sdp")
                    apiHandler.handleWebRtcAnswer(sdp)
                }
            }

            "webrtc/ice" -> {
                if (origin != Origin.WEBSOCKET_REVERSE) {
                    ApiResponse.Error("WebRTC signaling requires reverse WebSocket connection")
                } else {
                    val candidateSdp = params.getString("candidate")
                    val sdpMid = params.optString("sdpMid")
                    val sdpMLineIndex = params.optInt("sdpMLineIndex")
                    apiHandler.handleWebRtcIce(candidateSdp, sdpMid, sdpMLineIndex)
                }
            }

            else -> ApiResponse.Error("Unknown method: $method")
        }
    }
}
