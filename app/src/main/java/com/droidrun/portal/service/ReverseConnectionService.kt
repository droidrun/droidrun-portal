package com.droidrun.portal.service

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.droidrun.portal.api.ApiResponse
import com.droidrun.portal.config.ConfigManager
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject
import java.net.URI
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class ReverseConnectionService : Service() {

    companion object {
        private const val TAG = "ReverseConnService"
        private const val RECONNECT_DELAY_MS = 3000L
    }

    private val binder = LocalBinder()
    private lateinit var configManager: ConfigManager
    private lateinit var actionDispatcher: ActionDispatcher

    private var webSocketClient: WebSocketClient? = null
    private var isServiceRunning = AtomicBoolean(false)
    private val handler = Handler(Looper.getMainLooper())

    inner class LocalBinder : Binder() {
        fun getService(): ReverseConnectionService = this@ReverseConnectionService
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
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
        isServiceRunning.set(false)
        disconnect()
        Log.i(TAG, "Service Destroyed")
    }

    private fun connectToHost() {
        if (!isServiceRunning.get()) return

        val hostUrl = configManager.reverseConnectionUrl
        val authToken = configManager.reverseConnectionToken

        if (hostUrl.isBlank()) {
            Log.w(TAG, "No host URL configured")
            // Don't stop self, maybe user will config later? 
            // Or stop and let UI restart it.
            return
        }

        try {
            val uri = URI(hostUrl)
            val headers = mutableMapOf<String, String>()
            if (authToken.isNotBlank())
                headers["Authorization"] = "Bearer $authToken"

            headers["X-User-ID"] = "7785b089-b9aa-458d-a32e-baec315e5e16"
            headers["X-Device-ID"] = configManager.deviceID
            headers["X-Device-Name"] = configManager.deviceID
            headers["X-Device-Country"] = "de"


            webSocketClient = object : WebSocketClient(uri, headers) {
                override fun onOpen(handshakedata: ServerHandshake?) {
                    Log.i(TAG, "Connected to Host: $hostUrl")
                }

                override fun onMessage(message: String?) {
                    handleMessage(message)
                }

                override fun onClose(code: Int, reason: String?, remote: Boolean) {
                    Log.w(TAG, "Disconnected from Host: $reason")
                    scheduleReconnect()
                }

                override fun onError(ex: Exception?) {
                    Log.e(TAG, "Connection Error: ${ex?.message}")
                    scheduleReconnect()
                }
            }
            Log.i(TAG, "connecting to remote via websocket")
            webSocketClient?.connect()
            Log.i(TAG, "websocket connection established")
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

    private fun handleMessage(message: String?) {
        Log.d(TAG, "Received message: $message")
        if (message == null) return

        if (!::actionDispatcher.isInitialized) {
            val service = DroidrunAccessibilityService.getInstance()
            if (service == null) {
                Log.e(TAG, "Accessibility Service not ready, cannot dispatch command")
                return
            }
            actionDispatcher = service.getActionDispatcher()
        }

        try {
            val json = JSONObject(message)
            val id = json.getInt("id")
            val method = json.getString("method")
            val params = json.optJSONArray("params")?.optJSONObject(0) ?: JSONObject()

            Log.d(TAG, "Dispatching $method (id=$id,params=$params)")

            // Execute
            val result = actionDispatcher.dispatch(method, params)
            Log.d(TAG, "Command executed. Result type: ${result.javaClass.simpleName}")

            val resp = result.toJson(id)
            webSocketClient?.send(resp)
            Log.d(TAG, "Sent response: $resp")
        } catch (e: Exception) {
            Log.e(TAG, "Error processing message", e)
        }
    }
}
