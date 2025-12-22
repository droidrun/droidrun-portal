package com.droidrun.portal.service

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.droidrun.portal.streaming.WebRtcManager

class ScreenCaptureStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ScreenCaptureService.ACTION_STOP_STREAM) return

        val serviceIntent = Intent(context, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_STOP_STREAM
        }

        try {
            context.startService(serviceIntent)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to deliver stop action to service", e)
            val manager = WebRtcManager.getInstance(context)
            manager.notifyStreamStoppedAsync("user_stop")
            manager.stopStreamAsync()
        }

        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(ScreenCaptureService.NOTIFICATION_ID)
    }

    companion object {
        private const val TAG = "ScreenCaptureStopReceiver"
    }
}
