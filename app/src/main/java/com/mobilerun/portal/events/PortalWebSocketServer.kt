package com.mobilerun.portal.events

import android.os.SystemClock
import android.util.Log
import com.mobilerun.portal.events.model.EventType
import com.mobilerun.portal.events.model.PortalEvent
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import com.mobilerun.portal.service.ActionDispatcher
import com.mobilerun.portal.config.ConfigManager
import com.mobilerun.portal.service.WebSocketDispatchBucket
import com.mobilerun.portal.service.WebSocketDispatchPolicy
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
        private const val HTTP_UNAUTHORIZED_CODE = 401
        private const val EXPECTED_REQUEST_ID_BYTES = 36
        private const val UNAUTHORIZED = "Unauthorized"
    }

    private val signalingExecutor = Executors.newSingleThreadExecutor()
    private val commandExecutor = Executors.newSingleThreadExecutor()
    private val installExecutor = Executors.newSingleThreadExecutor()
    private val localDeviceEventRelay = LocalDeviceEventRelay(
        connectionsProvider = { connections.toList() },
        onSendFailure = { connection, error ->
            Log.e(
                TAG,
                "Failed to send local device event to ${connection.remoteSocketAddress}",
                error,
            )
        },
    )
    private val eventListener: (PortalEvent) -> Unit = { event ->
        localDeviceEventRelay.emit(event)
    }

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
        if (token.isNullOrEmpty()) {
            token = LocalDeviceEventRouting.extractToken(descriptor)
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
        if (conn != null) {
            localDeviceEventRelay.register(conn, handshake?.resourceDescriptor)
        }
        Log.d(TAG, "New connection from ${conn?.remoteSocketAddress}")
    }

    override fun onClose(conn: WebSocket?, code: Int, reason: String?, remote: Boolean) {
        Log.d(TAG, "Connection closed: $reason")
        localDeviceEventRelay.unregister(conn)
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

                val executor =
                    when (WebSocketDispatchPolicy.bucketForNormalizedMethod(normalizedMethod)) {
                        WebSocketDispatchBucket.SIGNALING -> signalingExecutor
                        WebSocketDispatchBucket.COMMAND -> commandExecutor
                        WebSocketDispatchBucket.INSTALL -> installExecutor
                    }
                executor.submit {
                    dispatchAndRespond(
                        conn = conn,
                        method = method,
                        normalizedMethod = normalizedMethod,
                        params = params,
                        requestId = id,
                    )
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
        localDeviceEventRelay.unregister(conn)
    }

    override fun onStart() {
        Log.i(TAG, "WebSocket Server started on port $port")
        onServerStarted?.invoke()

        // Register ourselves with the Hub to receive events
        EventHub.subscribe(eventListener)
    }

    private fun dispatchAndRespond(
        conn: WebSocket?,
        method: String,
        normalizedMethod: String,
        params: JSONObject,
        requestId: Any?,
    ) {
        val traceExecutionTiming = WebSocketDispatchPolicy.shouldTraceExecutionTiming(normalizedMethod)
        val startedAtMs = if (traceExecutionTiming) SystemClock.elapsedRealtime() else 0L
        try {
            val result = actionDispatcher.dispatch(
                method,
                params,
                origin = ActionDispatcher.Origin.WEBSOCKET_LOCAL,
                requestId = requestId,
            )
            if (traceExecutionTiming) {
                val elapsedMs = SystemClock.elapsedRealtime() - startedAtMs
                Log.d(
                    TAG,
                    "Completed $normalizedMethod (id=$requestId, elapsedMs=$elapsedMs, resultType=${result.javaClass.simpleName})",
                )
            }
            sendResponse(conn, result, requestId)
        } catch (e: Exception) {
            if (traceExecutionTiming) {
                val elapsedMs = SystemClock.elapsedRealtime() - startedAtMs
                Log.e(TAG, "Failed $normalizedMethod (id=$requestId, elapsedMs=$elapsedMs)", e)
            } else {
                Log.e(TAG, "Command execution failed for $method", e)
            }
            sendErrorResponse(conn, requestId, e.message ?: "unknown exception")
        }
    }

    private fun sendResponse(
        conn: WebSocket?,
        result: com.mobilerun.portal.api.ApiResponse,
        requestId: Any?,
    ) {
        if (conn?.isOpen != true) {
            Log.w(TAG, "Skipping response for closed local socket (id=$requestId)")
            return
        }

        if (result is com.mobilerun.portal.api.ApiResponse.Binary) {
            val idString = requestId.toString()
            val uuidBytes = idString.toByteArray(Charsets.UTF_8)
            if (uuidBytes.size != EXPECTED_REQUEST_ID_BYTES) {
                Log.w(
                    TAG,
                    "Unexpected request id size: ${uuidBytes.size} bytes (expected $EXPECTED_REQUEST_ID_BYTES)",
                )
            }

            val payload = ByteBuffer.allocate(uuidBytes.size + result.data.size)
            payload.put(uuidBytes)
            payload.put(result.data)
            payload.flip()
            conn.send(payload)
            return
        }

        conn.send(result.toJson(requestId))
    }

    private fun sendErrorResponse(conn: WebSocket?, requestId: Any?, message: String) {
        if (requestId == null) return
        if (conn?.isOpen == true) {
            conn.send(com.mobilerun.portal.api.ApiResponse.Error(message).toJson(requestId))
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
            EventHub.unsubscribe(eventListener)
            stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping server", e)
        } finally {
            try {
                signalingExecutor.shutdownNow()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping signaling executor", e)
            }
            try {
                commandExecutor.shutdownNow()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping command executor", e)
            }
            try {
                installExecutor.shutdownNow()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping install executor", e)
            }
        }
    }
}
