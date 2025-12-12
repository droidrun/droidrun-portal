package com.droidrun.portal.service

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.droidrun.portal.api.ApiHandler
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

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

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

        // We need to add host URL to config first!
        // For now, let's assume it's stored or passed.
        // Let's rely on ConfigManager to have a new field 'reverseConnectionUrl'
        val hostUrl = configManager.reverseConnectionUrl 
        val authToken = configManager.authToken

        if (hostUrl.isBlank()) {
            Log.w(TAG, "No host URL configured")
            // Don't stop self, maybe user will config later? 
            // Or stop and let UI restart it.
            return
        }

        try {
            val uri = URI(hostUrl)
            // Add Auth header
            val headers = mutableMapOf<String, String>()
            headers["Authorization"] = "Bearer $authToken"

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
            webSocketClient?.connect()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to initiate connection", e)
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        if (!isServiceRunning.get()) return
        
        handler.postDelayed({
            if (isServiceRunning.get()) {
                Log.d(TAG, "Attempting reconnect...")
                connectToHost()
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
        if (message == null) return
        
        // Lazy init actionDispatcher if needed
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
            val id = json.optString("id")
            val method = json.optString("method")
            
            if (id.isNotEmpty() && method.isNotEmpty()) {
                val params = json.optJSONObject("params") ?: JSONObject()
                
                // Execute
                val result = actionDispatcher.dispatch(method, params)
                
                // Response
                if (result is ApiResponse.Binary) {
                    // Binary Response
                    val uuidBytes = id.toByteArray(Charsets.UTF_8)
                    val payload = ByteBuffer.allocate(uuidBytes.size + result.data.size)
                    payload.put(uuidBytes)
                    payload.put(result.data)
                    payload.flip()
                    webSocketClient?.send(payload)
                } else {
                    // Text Response
                    val response = JSONObject()
                    response.put("id", id)
                    
                    val apiResponseJson = JSONObject(result.toJson())
                    if (apiResponseJson.getString("status") == "success") {
                        response.put("status", "success")
                        response.put("result", apiResponseJson.opt("data"))
                    } else {
                        response.put("status", "error")
                        response.put("error", apiResponseJson.opt("error"))
                    }
                    webSocketClient?.send(response.toString())
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing message", e)
        }
    }
}
