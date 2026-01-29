package com.droidrun.portal.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.droidrun.portal.R
import com.droidrun.portal.api.ApiResponse
import com.droidrun.portal.config.ConfigManager
import com.droidrun.portal.state.ConnectionState
import com.droidrun.portal.state.ConnectionStateManager
import com.droidrun.portal.streaming.WebRtcManager
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class ReverseConnectionService : Service() {

    companion object {
        private const val TAG = "ReverseConnService"
        private const val CHANNEL_ID = "reverse_connection_channel"
        private const val NOTIFICATION_ID = 2002
        private const val RECONNECT_DELAY_MS = 3000L
        private const val TOAST_DEBOUNCE_MS = 60_000L
        private const val CONNECTION_LOST_TIMEOUT_SEC = 30
        const val ACTION_DISCONNECT = "com.droidrun.portal.action.REVERSE_DISCONNECT"

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
    private var isForeground = false

    @Volatile
    private var foregroundSuppressed = false

    inner class LocalBinder : Binder() {
        fun getService(): ReverseConnectionService = this@ReverseConnectionService
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        instance = this
        configManager = ConfigManager.getInstance(this)
        createNotificationChannel()
        Log.d(TAG, "Service Created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: intent=$intent, action=${intent?.action}, flags=$flags, startId=$startId")
        if (intent?.action == ACTION_DISCONNECT) {
            Log.i(TAG, "onStartCommand: Disconnect requested via notification")
            disconnectByUser()
            return START_NOT_STICKY
        }
        Log.d(TAG, "onStartCommand: Ensuring foreground...")
        ensureForeground()
        val wasRunning = isServiceRunning.getAndSet(true)
        Log.d(TAG, "onStartCommand: wasRunning=$wasRunning, now isServiceRunning=${isServiceRunning.get()}")
        if (!wasRunning) {
            Log.i(TAG, "onStartCommand: Starting Reverse Connection Service, calling connectToHost()")
            connectToHost()
        } else {
            Log.d(TAG, "onStartCommand: Service already running, skipping connectToHost()")
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
        if (isForeground) {
            @Suppress("DEPRECATION")
            stopForeground(true)
            isForeground = false
        }
        Log.i(TAG, "Service Destroyed")
    }


    fun sendText(text: String): Boolean {
        val client = webSocketClient
        if (client != null && client.isOpen) {
            client.send(text)
            return true
        }
        return false
    }

    fun buildHeaders(): MutableMap<String, String> {
        val authToken = configManager.reverseConnectionToken

        val headers = mutableMapOf<String, String>()
        if (authToken.isNotBlank())
            headers["Authorization"] = "Bearer $authToken"

        headers["X-Device-ID"] = configManager.deviceID
        headers["X-Device-Name"] = configManager.deviceName
        headers["X-Device-Country"] = configManager.deviceCountryCode

        val serviceKey = configManager.reverseConnectionServiceKey
        if (serviceKey.isNotBlank()) {
            headers["X-Remote-Device-Key"] = serviceKey
        }

        return headers
    }

    private fun connectToHost() {
        Log.d(TAG, "connectToHost: called, isServiceRunning=${isServiceRunning.get()}")
        if (!isServiceRunning.get()) {
            Log.w(TAG, "connectToHost: Service not running, aborting")
            return
        }

        val hostUrl = configManager.reverseConnectionUrlOrDefault
        Log.d(TAG, "connectToHost: hostUrl='$hostUrl'")
        if (hostUrl.isBlank()) {
            Log.w(TAG, "connectToHost: No host URL configured")
            ConnectionStateManager.setState(ConnectionState.DISCONNECTED)
            return
        }

        try {
            Log.d(TAG, "connectToHost: Setting state to CONNECTING")
            ConnectionStateManager.setState(ConnectionState.CONNECTING)
            disconnect() // Prevent resource leaks from zombie connections
            val deviceId = configManager.deviceID
            val finalUrl = hostUrl.replace("{deviceId}", deviceId)
            Log.d(TAG, "connectToHost: deviceId='$deviceId', finalUrl='$finalUrl'")
            val uri = URI(finalUrl)
            val headers = buildHeaders()
            Log.d(TAG, "connectToHost: headers=${headers.keys.joinToString()}")

            webSocketClient = object : WebSocketClient(uri, headers) {
                override fun onOpen(handshakedata: ServerHandshake?) {
                    Log.i(TAG, "onOpen: Connected to Host: $hostUrl, status=${handshakedata?.httpStatus}, message=${handshakedata?.httpStatusMessage}")
                    ConnectionStateManager.setState(ConnectionState.CONNECTED)
                    showReverseConnectionToastIfEnoughTimeIsPassed()
                    WebRtcManager.getExistingInstance()?.let { manager ->
                        manager.setReverseConnectionService(this@ReverseConnectionService)
                        manager.onReverseConnectionOpen()
                    }

                }

                override fun onMessage(message: String?) {
                    handleMessage(message)
                }

                override fun onClose(code: Int, reason: String?, remote: Boolean) {
                    Log.w(TAG, "Disconnected from Host: code=$code reason=$reason remote=$remote")
                    logNetworkState("onClose")

                    if (reason != null) {
                        if (reason.contains("401") || reason.contains("Unauthorized")) {
                            ConnectionStateManager.setState(ConnectionState.UNAUTHORIZED)
                            handleWsDisconnected()
                            // Do not reconnect automatically on auth error
                            return
                        } else if (reason.contains("403") || reason.contains("Forbidden")) {
                            // 403 Forbidden = device limit reached
                            Log.w(TAG, "onClose: 403 Forbidden - device limit reached")
                            ConnectionStateManager.setState(ConnectionState.LIMIT_EXCEEDED)
                            handleWsDisconnected()
                            return
                        } else if (reason.contains("400") || reason.contains("Bad Request")) {
                            // 400 Bad Request - missing headers or invalid request
                            Log.w(TAG, "onClose: 400 Bad Request - check required headers (X-Device-Name, X-Device-Country)")
                            ConnectionStateManager.setState(ConnectionState.BAD_REQUEST)
                            handleWsDisconnected()
                            return
                        }
                    }

                    ConnectionStateManager.setState(ConnectionState.DISCONNECTED)
                    handleWsDisconnected()
                    scheduleReconnect()
                }

                override fun onError(ex: Exception?) {
                    Log.e(TAG, "onError: Connection Error: ${ex?.javaClass?.simpleName}: ${ex?.message}", ex)
                    logNetworkState("onError")
                    // Don't set state here, onClose usually follows
                    if (webSocketClient == null || webSocketClient?.isOpen != true)
                        handleWsDisconnected()

                    scheduleReconnect()
                }
            }
            Log.i(TAG, "connectToHost: Created WebSocketClient, calling connect()...")
            webSocketClient?.connectionLostTimeout = CONNECTION_LOST_TIMEOUT_SEC
            webSocketClient?.connect()
            Log.i(TAG, "connectToHost: connect() called, waiting for callbacks...")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initiate connection", e)
            ConnectionStateManager.setState(ConnectionState.DISCONNECTED)
            scheduleReconnect()
        }
    }

    private var isReconnecting = AtomicBoolean(false)

    private fun scheduleReconnect() {
        if (!isServiceRunning.get()) return
        if (isReconnecting.getAndSet(true)) return // Already scheduled

        ConnectionStateManager.setState(ConnectionState.RECONNECTING)
        Log.d(TAG, "Scheduling reconnect in ${RECONNECT_DELAY_MS}ms")
        handler.postDelayed({
            if (isServiceRunning.get()) {
                isReconnecting.set(false)
                Log.d(TAG, "Attempting reconnect...")
                connectToHost()
            } else {
                isReconnecting.set(false)
                ConnectionStateManager.setState(ConnectionState.DISCONNECTED)
            }
        }, RECONNECT_DELAY_MS)
    }

    private fun disconnect() {
        try {
            webSocketClient?.close()
            webSocketClient = null
            ConnectionStateManager.setState(ConnectionState.DISCONNECTED)
        } catch (e: Exception) {
            Log.e(TAG, "Error closing connection", e)
        }
    }

    private fun disconnectByUser() {
        configManager.reverseConnectionEnabled = false
        isServiceRunning.set(false)
        isReconnecting.set(false)
        handler.removeCallbacksAndMessages(null)
        ScreenCaptureService.requestStop("user_disconnect")
        disconnect()
        stopSelf()
    }

    private fun handleWsDisconnected() {
        val manager = WebRtcManager.getExistingInstance()
        if (manager != null) {
            if (!manager.getStreamRequestId().isNullOrBlank()) {
                manager.notifyStreamStoppedAsync("ws_disconnected")
            }
            manager.requestGracefulStop("ws_disconnected")
        } else {
            ScreenCaptureService.requestStop("ws_disconnected")
        }
    }

    private fun logNetworkState(prefix: String) {
        val connectivityManager =
            getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val network = connectivityManager?.activeNetwork
        val capabilities = connectivityManager?.getNetworkCapabilities(network)
        if (capabilities == null) {
            Log.d(TAG, "$prefix network=unknown")
            return
        }
        val transports = mutableListOf<String>()
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) transports.add("wifi")
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) transports.add("cellular")
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) transports.add("ethernet")
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) transports.add("vpn")
        val validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        Log.d(TAG, "$prefix network=${transports.joinToString(",")} validated=$validated")
    }

    private fun ensureForeground() {
        if (isForeground || foregroundSuppressed) return
        try {
            val notification = createNotification()
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
            isForeground = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service", e)
        }
    }

    fun suspendForegroundForStreaming() {
        foregroundSuppressed = true
        if (!isForeground) return
        try {
            @Suppress("DEPRECATION")
            stopForeground(true)
            isForeground = false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop foreground service", e)
        }
    }

    fun resumeForegroundAfterStreaming() {
        foregroundSuppressed = false
        ensureForeground()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Reverse Connection",
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, com.droidrun.portal.ui.MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val disconnectIntent = Intent(this, ReverseConnectionService::class.java).apply {
            action = ACTION_DISCONNECT
        }
        val disconnectPendingIntent = PendingIntent.getService(
            this,
            1,
            disconnectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.reverse_connection_service_title))
            .setContentText(getString(R.string.reverse_connection_service_text))
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.reverse_connection_disconnect_action),
                disconnectPendingIntent
            )
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
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
            // Check if the message is a valid JSON before parsing
            if (!message.trim().startsWith("{") && !message.trim().startsWith("[")) {
                Log.w(TAG, "Received non-JSON message: $message")
                return
            }

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
