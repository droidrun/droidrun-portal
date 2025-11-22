package com.droidrun.portal.events

import android.util.Log
import com.droidrun.portal.events.model.EventType
import com.droidrun.portal.events.model.PortalEvent
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import java.nio.ByteBuffer

class PortalWebSocketServer(port: Int) : WebSocketServer(InetSocketAddress(port)) {
    
    companion object {
        private const val TAG = "PortalWSServer"
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
            // Handle incoming commands from client
            val commandEvent = PortalEvent.fromJson(message)
            handleCommand(conn, commandEvent)
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing message: ${e.message}")
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
        }
    }
}
