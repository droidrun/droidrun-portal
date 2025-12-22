package com.droidrun.portal.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.droidrun.portal.streaming.WebRtcManager

class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val CHANNEL_ID = "screen_capture_channel"
        private const val NOTIFICATION_ID = 2001
        
        const val ACTION_START_STREAM = "com.droidrun.portal.action.START_STREAM"
        const val ACTION_STOP_STREAM = "com.droidrun.portal.action.STOP_STREAM"
        const val ACTION_PERMISSION_RESULT = "com.droidrun.portal.action.PERMISSION_RESULT"
        
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        
        const val EXTRA_WIDTH = "width"
        const val EXTRA_HEIGHT = "height"
        const val EXTRA_FPS = "fps"
    }

    private lateinit var webRtcManager: WebRtcManager

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "ScreenCaptureService Created")
        
        webRtcManager = WebRtcManager.getInstance(this)
        
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY

        when (intent.action) {
            ACTION_PERMISSION_RESULT -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                val width = intent.getIntExtra(EXTRA_WIDTH, 720)
                val height = intent.getIntExtra(EXTRA_HEIGHT, 1280)
                val fps = intent.getIntExtra(EXTRA_FPS, 30)

                if (resultCode == -1 && resultData != null) {
                    startForeground(NOTIFICATION_ID, createNotification(), 
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                             ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                        } else {
                            0
                        }
                    )
                    
                    val rcs = ReverseConnectionService.getInstance()
                    if (rcs == null) {
                        Log.e(TAG, "ReverseConnectionService is null - cannot send signaling messages, aborting stream")
                        webRtcManager.setStreamRequestId(null)
                        stopSelf()
                        return START_NOT_STICKY
                    }
                    webRtcManager.setReverseConnectionService(rcs)

                    val streamRequestId = webRtcManager.getStreamRequestId()
                    try {
                        webRtcManager.startStream(resultData, width, height, fps)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to start stream", e)
                        try {
                            val errorJson = org.json.JSONObject().apply {
                                put("method", "stream/error")
                                put("params", org.json.JSONObject().apply {
                                    put("error", "capture_failed")
                                    put("message", e.message ?: "Failed to start screen capture")
                                    if (streamRequestId != null) put("request_id", streamRequestId)

                                })
                            }
                            rcs.sendText(errorJson.toString())
                        } catch (jsonEx: Exception) {
                            Log.e(TAG, "Failed to send stream error", jsonEx)
                        }
                        webRtcManager.setStreamRequestId(null)
                        @Suppress("DEPRECATION")
                        stopForeground(true)
                        stopSelf()
                        return START_NOT_STICKY
                    }
                } else {
                    Log.e(TAG, "Invalid permission result")
                    webRtcManager.setStreamRequestId(null)
                    stopSelf()
                }
            }
            ACTION_STOP_STREAM -> {
                Log.i(TAG, "Stopping Stream via Action")
                stopStream()
            }
        }

        return START_NOT_STICKY
    }
    
    private fun stopStream() {
        webRtcManager.stopStream()
        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "ScreenCaptureService Destroyed")
        webRtcManager.stopStream()
        @Suppress("DEPRECATION")
        stopForeground(true) // Ensure notification is cleared
    }

    override fun onBind(intent: Intent?): IBinder? = null
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Screen Streaming",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(): Notification {
        val stopIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ACTION_STOP_STREAM
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Screen Streaming Active")
            .setContentText("Droidrun Portal is sharing your screen")
            .setSmallIcon(android.R.drawable.ic_menu_camera) // Use a better icon if available
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Streaming", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }
}
