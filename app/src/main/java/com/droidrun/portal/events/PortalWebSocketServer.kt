package com.droidrun.portal.events

import android.util.Log
import com.droidrun.portal.events.model.EventType
import com.droidrun.portal.events.model.PortalEvent
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import com.droidrun.portal.service.ActionDispatcher
import com.droidrun.portal.config.ConfigManager
import org.json.JSONObject
import java.util.concurrent.Executors

class PortalWebSocketServer(
    port: Int,
    private val actionDispatcher: ActionDispatcher,
    private val configManager: ConfigManager,
    private val onServerStarted: (() -> Unit)? = null,
) : WebSocketServer(InetSocketAddress(port)) {

    companion object {
        private const val TAG = "PortalWSServer"
        private const val AUTHORIZATION_HEADER = "Authorization"
        private const val BEARER_PREFIX = "Bearer "
        private const val TOKEN_QUERY_PARAM_PREFIX = "token="
        private const val HTTP_UNAUTHORIZED_CODE = 401
        private const val EXPECTED_REQUEST_ID_BYTES = 36
        private const val UNAUTHORIZED = "Unauthorized"
    }

    private val installExecutor = Executors.newSingleThreadExecutor()

    override fun onWebsocketHandshakeReceivedAsServer(
        conn: WebSocket?,
        draft: org.java_websocket.drafts.Draft?,
        request: ClientHandshake?
    ): org.java_websocket.handshake.ServerHandshakeBuilder {
        val descriptor = request?.resourceDescriptor ?: ""

        // Check for token in header
        var token = request?.getFieldValue(AUTHORIZATION_HEADER)
        if (!token.isNullOrEmpty() && token.startsWith(BEARER_PREFIX)) {
            token = token.removePrefix(BEARER_PREFIX).trim()
        }

        // Fallback: Check query param (e.g. /?token=abc)
        if (token.isNullOrEmpty() && descriptor.contains(TOKEN_QUERY_PARAM_PREFIX)) {
            val query = descriptor.substringAfter("?")
            val params = query.split("&")
            for (param in params) {
                if (param.startsWith(TOKEN_QUERY_PARAM_PREFIX)) {
                    token = param.removePrefix(TOKEN_QUERY_PARAM_PREFIX)
                    break
                }
            }
        }

        // Validate Token
        if (token != configManager.authToken) {
            Log.w(TAG, "Rejecting connection: Invalid or missing token")
            throw org.java_websocket.exceptions.InvalidDataException(
                HTTP_UNAUTHORIZED_CODE,
                UNAUTHORIZED,
            )
        }

        return super.onWebsocketHandshakeReceivedAsServer(conn, draft, request)
    }

    override fun onOpen(conn: WebSocket?, handshake: ClientHandshake?) {
        Log.d(TAG, "New connection from ${conn?.remoteSocketAddress}")
    }

    override fun onClose(conn: WebSocket?, code: Int, reason: String?, remote: Boolean) {
        Log.d(TAG, "Connection closed: $reason")
    }

    override fun onMessage(conn: WebSocket?, message: String?) {
        if (message == null) return

        try {
            val json = JSONObject(message)
            // Use opt() to preserve ID type (number vs string) for JSON-RPC compliance
            val id = json.opt("id")?.takeIf { it != JSONObject.NULL }
            val method = json.optString("method")

            if (id != null && method.isNotEmpty()) {
                // Command Request
                val params = json.optJSONObject("params") ?: JSONObject()

                val normalizedMethod =
                    method.removePrefix("/action/").removePrefix("action.").removePrefix("/")

                // Don't block ws
                if (normalizedMethod == "install") {
                    installExecutor.submit {
                        try {
                            val result = actionDispatcher.dispatch(
                                method,
                                params,
                                origin = ActionDispatcher.Origin.WEBSOCKET_LOCAL,
                                requestId = id,
                            )
                            if (conn?.isOpen == true) conn.send(result.toJson(id))

                        } catch (e: Exception) {
                            Log.e(TAG, "Install task failed: ${e.message}", e)
                            try {
                                if (conn?.isOpen == true) {
                                    conn.send(
                                        com.droidrun.portal.api.ApiResponse.Error(
                                            e.message ?: "Install failed",
                                        ).toJson(id),
                                    )
                                }
                            } catch (_: Exception) { }
                        }
                    }
                    return
                }

                val result = actionDispatcher.dispatch(
                    method,
                    params,
                    origin = ActionDispatcher.Origin.WEBSOCKET_LOCAL,
                    requestId = id,
                )

                if (result is com.droidrun.portal.api.ApiResponse.Binary) {
                    // Binary Response: [UUID (36 bytes)] + [Data]
                    val idString = id.toString()
                    val uuidBytes = idString.toByteArray(Charsets.UTF_8)
                    // ensuring UUID is 36 bytes (it should be if generated by python uuid4)
                    // If not, maybe need padding or fixed size.
                    // Python UUID str is 36 chars = 36 bytes in UTF-8/ASCII.
                    if (uuidBytes.size != EXPECTED_REQUEST_ID_BYTES)
                        Log.w(
                            TAG,
                            "Unexpected request id size: ${uuidBytes.size} bytes (expected $EXPECTED_REQUEST_ID_BYTES)",
                        )


                    val payload = ByteBuffer.allocate(uuidBytes.size + result.data.size)
                    payload.put(uuidBytes)
                    payload.put(result.data)
                    payload.flip()
                    conn?.send(payload)
                } else {
                    // ApiResponse knows how to format itself with the id
                    conn?.send(result.toJson(id))
                }

            } else {
                // Fallback for legacy events (if any) or ping
                val commandEvent = PortalEvent.fromJson(message)
                handleCommand(conn, commandEvent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing message: ${e.message}")
        }
    }

    override fun onMessage(conn: WebSocket?, message: ByteBuffer?) {
        // Handle binary messages if needed
    }

    override fun onError(conn: WebSocket?, ex: Exception?) {
        Log.e(TAG, "WebSocket Error: ${ex?.message}")
    }

    override fun onStart() {
        Log.i(TAG, "WebSocket Server started on port $port")
        onServerStarted?.invoke()

        // Register ourselves with the Hub to receive events
        EventHub.subscribe { event ->
            broadcast(event.toJson())
        }
    }

    private fun handleCommand(conn: WebSocket?, event: PortalEvent) {
        when (event.type) {
            EventType.PING -> {
                val pong = PortalEvent(EventType.PONG, payload = "pong")
                conn?.send(pong.toJson())
            }

            else -> {
                Log.d(TAG, "Received unhandled event: ${event.type}")
            }
        }
    }

    // Helper to safely stop
    fun stopSafely() {
        try {
            stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping server", e)
        } finally {
            try {
                installExecutor.shutdownNow()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping install executor", e)
            }
        }
    }
}
