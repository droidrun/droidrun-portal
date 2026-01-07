package com.droidrun.portal.service

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import com.droidrun.portal.R
import com.droidrun.portal.api.ApiResponse
import com.droidrun.portal.config.ConfigManager
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class ReverseConnectionService : Service() {

    companion object {
        private const val TAG = "ReverseConnService"
        private const val RECONNECT_DELAY_MS = 3000L
        private const val TOAST_DEBOUNCE_MS = 60_000L

        @Volatile
        private var instance: ReverseConnectionService? = null

        fun getInstance(): ReverseConnectionService? = instance
    }

    private val binder = LocalBinder()
    private lateinit var configManager: ConfigManager
    private lateinit var actionDispatcher: ActionDispatcher

    private var webSocketClient: WebSocketClient? = null
    private var isServiceRunning = AtomicBoolean(false)
    private val handler = Handler(Looper.getMainLooper())
    private val installExecutor = Executors.newSingleThreadExecutor()
    private var lastReverseToastAtMs = 0L

    inner class LocalBinder : Binder() {
        fun getService(): ReverseConnectionService = this@ReverseConnectionService
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        instance = this
        configManager = ConfigManager.getInstance(this)
        Log.d(TAG, "Service Created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isServiceRunning.getAndSet(true)) {
            Log.i(TAG, "Starting Reverse Connection Service")
            connectToHost()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        isServiceRunning.set(false)
        handler.removeCallbacksAndMessages(null)
        disconnect()
        try {
            installExecutor.shutdownNow()
        } catch (_: Exception) {
        }
        Log.i(TAG, "Service Destroyed")
    }


    fun sendText(text: String) {
        webSocketClient?.let { client ->
            if (client.isOpen) client.send(text)
        }
    }

    fun buildHeaders(): MutableMap<String, String> {
        val authToken = configManager.reverseConnectionToken

        val headers = mutableMapOf<String, String>()
        if (authToken.isNotBlank())
            headers["Authorization"] = "Bearer $authToken"

        val userID = configManager.userID
        if (userID.isNotBlank())
            headers["X-User-ID"] = userID

        headers["X-Device-ID"] = configManager.deviceID
        headers["X-Device-Name"] = configManager.deviceName
        headers["X-Device-Country"] = configManager.deviceCountryCode

        headers["X-Remote-Device-Key"] = "<placeholder>"

        return headers
    }

    private fun connectToHost() {
        if (!isServiceRunning.get()) return

        val hostUrl = configManager.reverseConnectionUrlOrDefault
        if (hostUrl.isBlank()) {
            Log.w(TAG, "No host URL configured")
            // Don't stop self, maybe user will config later? 
            // Or stop and let UI restart it.
            return
        }

        try {
            disconnect() // Prevent resource leaks from zombie connections
            val uri = URI(hostUrl.replace("{deviceId}", configManager.deviceID))
            val headers = buildHeaders()

            webSocketClient = object : WebSocketClient(uri, headers) {
                override fun onOpen(handshakedata: ServerHandshake?) {
                    Log.i(TAG, "Connected to Host: $hostUrl")
                    showReverseConnectionToastIfEnoughTimeIsPassed()
                }

                override fun onMessage(message: String?) {
                    handleMessage(message)
                }

                override fun onClose(code: Int, reason: String?, remote: Boolean) {
                    Log.w(TAG, "Disconnected from Host: $reason")
                    handleWsDisconnected()
                    scheduleReconnect()
                }

                override fun onError(ex: Exception?) {
                    Log.e(TAG, "Connection Error: ${ex?.message}")
                    if (webSocketClient == null || webSocketClient?.isOpen != true)
                        handleWsDisconnected()

                    scheduleReconnect()
                }
            }
            Log.i(TAG, "connecting to remote via websocket")
            webSocketClient?.connect()
            Log.i(TAG, "websocket connection attempt started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initiate connection", e)
            scheduleReconnect()
        }
    }

    private var isReconnecting = AtomicBoolean(false)

    private fun scheduleReconnect() {
        if (!isServiceRunning.get()) return
        if (isReconnecting.getAndSet(true)) return // Already scheduled

        Log.d(TAG, "Scheduling reconnect in ${RECONNECT_DELAY_MS}ms")
        handler.postDelayed({
            if (isServiceRunning.get()) {
                isReconnecting.set(false)
                Log.d(TAG, "Attempting reconnect...")
                connectToHost()
            } else {
                isReconnecting.set(false)
            }
        }, RECONNECT_DELAY_MS)
    }

    private fun disconnect() {
        try {
            webSocketClient?.close()
            webSocketClient = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing connection", e)
        }
    }

    private fun handleWsDisconnected() {
        ScreenCaptureService.requestStop("ws_disconnected")
    }

    private fun showReverseConnectionToastIfEnoughTimeIsPassed() {
        val now = SystemClock.elapsedRealtime()
        if (lastReverseToastAtMs == 0L || now - lastReverseToastAtMs >= TOAST_DEBOUNCE_MS) {
            lastReverseToastAtMs = now
            handler.post {
                Toast.makeText(
                    this@ReverseConnectionService,
                    getString(R.string.reverse_connection_connected),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    private fun handleMessage(message: String?) {
        if (message == null) return
        // Truncate log to avoid spamming with large SDP/ICE payloads
        val logMsg = if (message.length > 200) message.take(200) + "..." else message
        Log.d(TAG, "Received message: $logMsg")

        var id: Any? = null

        try {
            val json = JSONObject(message)
            // Support both integer and string IDs (e.g., UUIDs)
            id = json.opt("id")?.takeIf { it != JSONObject.NULL }

            if (!::actionDispatcher.isInitialized) {
                synchronized(this) {
                    if (!::actionDispatcher.isInitialized) {
                        val service = DroidrunAccessibilityService.getInstance()
                        if (service == null) {
                            Log.e(TAG, "Accessibility Service not ready, cannot dispatch command")
                            webSocketClient?.send(
                                ApiResponse.Error("Accessibility Service not ready, cannot dispatch command")
                                    .toJson(id)
                            )
                            return
                        }
                        actionDispatcher = service.getActionDispatcher()
                    }
                }
            }

            // Method may be empty for JSON-RPC responses to outgoing messages (e.g., webrtc/offer)
            val method = json.optString("method", "")

            if (method.isEmpty()) {
                if (json.has("result")) {
                    Log.d(TAG, "Received JSON-RPC result for id=$id")
                } else if (json.has("error")) {
                    Log.w(TAG, "Received JSON-RPC error for id=$id: ${json.opt("error")}")
                } else {
                    Log.w(TAG, "Received message without method, result, or error: $message")
                }
                return
            }

            val params = json.optJSONObject("params") ?: JSONObject()

            // Truncate params log to avoid spamming with large SDP/ICE payloads
            val paramsLog =
                params.toString().let { if (it.length > 100) it.take(100) + "..." else it }
            Log.d(TAG, "Dispatching $method (id=$id, params=$paramsLog)")

            val normalizedMethod =
                method.removePrefix("/action/").removePrefix("action.").removePrefix("/")

            // Don't block ws
            if (normalizedMethod == "install") {
                val requestId = id
                installExecutor.submit {
                    try {
                        val result = actionDispatcher.dispatch(
                            method,
                            params,
                            origin = ActionDispatcher.Origin.WEBSOCKET_REVERSE,
                            requestId = requestId,
                        )
                        webSocketClient?.send(result.toJson(requestId))
                    } catch (e: Exception) {
                        Log.e(TAG, "Install task failed", e)
                        try {
                            webSocketClient?.send(
                                ApiResponse.Error(e.message ?: "Install failed").toJson(requestId),
                            )
                        } catch (_: Exception) {
                        }
                    }
                }
                return
            }

            // Execute
            val result = actionDispatcher.dispatch(
                method,
                params,
                origin = ActionDispatcher.Origin.WEBSOCKET_REVERSE,
                requestId = id,
            )
            Log.d(TAG, "Command executed. Result type: ${result.javaClass.simpleName}")

            val resp = result.toJson(id)
            webSocketClient?.send(resp)
            Log.d(TAG, "Sent response: $resp")
        } catch (e: Exception) {
            Log.e(TAG, "Error processing message", e)
            if (id != null) {
                try {
                    webSocketClient?.send(
                        ApiResponse.Error(e.message ?: "unknown exception").toJson(id)
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Error responding with an error")
                }
            }
        }
    }
}
