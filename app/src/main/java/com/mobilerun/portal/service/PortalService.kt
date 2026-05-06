package com.mobilerun.portal.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.mobilerun.portal.api.ApiHandler
import com.mobilerun.portal.config.ConfigManager
import com.mobilerun.portal.core.StateRepository
import com.mobilerun.portal.events.EventHub
import com.mobilerun.portal.events.PortalWebSocketServer
import com.mobilerun.portal.input.MobilerunKeyboardIME
import com.mobilerun.portal.triggers.TriggerRuntime

/**
 * Foreground service that hosts local HTTP and WebSocket servers without requiring
 * the Android AccessibilityService. Gesture, UI-tree, overlay, and screenshot APIs
 * remain unavailable because the ApiHandler is backed by a null accessibility service.
 */
class PortalService : Service(), ConfigManager.ConfigChangeListener {

    companion object {
        private const val TAG = "PortalService"
        private const val CHANNEL_ID = "portal_service_channel"
        private const val NOTIFICATION_ID = 3002

        @Volatile
        private var instance: PortalService? = null
        fun getInstance(): PortalService? = instance
    }

    private lateinit var configManager: ConfigManager
    private var socketServer: SocketServer? = null
    private var websocketServer: PortalWebSocketServer? = null
    private var actionDispatcher: ActionDispatcher? = null

    override fun onCreate() {
        super.onCreate()
        configManager = ConfigManager.getInstance(this)
        configManager.addListener(this)

        EventHub.init(configManager)
        TriggerRuntime.initialize(this)

        val stateRepo = StateRepository(service = null)
        val apiHandler = ApiHandler(
            stateRepo = stateRepo,
            getKeyboardIME = { MobilerunKeyboardIME.getInstance() },
            getPackageManager = { packageManager },
            appVersionProvider = {
                try {
                    packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"
                } catch (_: Exception) {
                    "unknown"
                }
            },
            context = this,
        )
        actionDispatcher = ActionDispatcher(apiHandler)
        socketServer = SocketServer(apiHandler, configManager, actionDispatcher!!)

        instance = this
        Log.i(TAG, "created (no-a11y mode)")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Portal Active")
            .setContentText("No-a11y mode")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)

        startSocketServerIfEnabled()
        startWebSocketServerIfEnabled()

        Log.i(TAG, "started as foreground service")
        return START_STICKY
    }

    override fun onDestroy() {
        stopSocketServer()
        stopWebSocketServer()
        configManager.removeListener(this)
        instance = null
        Log.i(TAG, "destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startSocketServerIfEnabled() {
        if (configManager.socketServerEnabled) startSocketServer()
    }

    private fun startSocketServer() {
        socketServer?.let { server ->
            if (!server.isRunning()) {
                val port = configManager.socketServerPort
                if (server.start(port)) {
                    Log.i(TAG, "Socket server started on port $port")
                } else {
                    Log.e(TAG, "Failed to start socket server on port $port")
                }
            }
        }
    }

    private fun stopSocketServer() {
        socketServer?.let { if (it.isRunning()) it.stop() }
    }

    private fun startWebSocketServerIfEnabled() {
        if (configManager.websocketEnabled) startWebSocketServer()
    }

    private fun startWebSocketServer() {
        if (!configManager.websocketEnabled) return
        val port = configManager.websocketPort
        try {
            val dispatcher = actionDispatcher ?: return
            val server = PortalWebSocketServer(
                port = port,
                actionDispatcher = dispatcher,
                configManager = configManager,
                onServerStarted = { Log.i(TAG, "WebSocket server started on port $port") },
            )
            websocketServer?.stopSafely()
            websocketServer = server
            server.start()
            Log.i(TAG, "WebSocket server startup initiated on port $port")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start WebSocket server on port $port", e)
        }
    }

    private fun stopWebSocketServer() {
        try {
            websocketServer?.stopSafely()
            websocketServer = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping WebSocket server", e)
        }
    }

    override fun onSocketServerEnabledChanged(enabled: Boolean) {
        if (enabled) startSocketServer() else stopSocketServer()
    }

    override fun onSocketServerPortChanged(port: Int) {
        socketServer?.let { server ->
            val wasRunning = server.isRunning()
            if (wasRunning) server.stop()
            if (wasRunning || configManager.socketServerEnabled) {
                if (server.start(port)) Log.i(TAG, "Socket server restarted on port $port")
                else Log.e(TAG, "Failed to restart socket server on port $port")
            }
        }
    }

    override fun onWebSocketEnabledChanged(enabled: Boolean) {
        if (enabled) startWebSocketServer() else stopWebSocketServer()
    }

    override fun onWebSocketPortChanged(port: Int) {
        if (!configManager.websocketEnabled) return
        stopWebSocketServer()
        startWebSocketServer()
    }

    override fun onOverlayVisibilityChanged(visible: Boolean) {}
    override fun onOverlayOffsetChanged(offset: Int) {}
    override fun onKeepScreenAwakeEnabledChanged(enabled: Boolean) {}
    override fun onProductionModeChanged(enabled: Boolean) {}

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Portal Service",
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }
}
