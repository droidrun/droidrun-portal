package com.droidrun.portal.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log
import com.droidrun.portal.service.ReverseConnectionService
import com.droidrun.portal.service.ScreenCaptureService
import com.droidrun.portal.streaming.WebRtcManager
import org.json.JSONObject

/**
 * Invisible activity to handle the MediaProjection permission request.
 * It is launched by the service/dispatcher, requests permission, and passes the result to the ScreenCaptureService.
 */
class ScreenCaptureActivity : Activity() {

    companion object {
        private const val REQUEST_CODE_CAPTURE_PERM = 1001
        private const val TAG = "ScreenCaptureActivity"
    }

    private lateinit var mediaProjectionManager: MediaProjectionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mediaProjectionManager.createScreenCaptureIntent(), REQUEST_CODE_CAPTURE_PERM)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST_CODE_CAPTURE_PERM) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                // Pass the permission result to the service
                val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                    action = ScreenCaptureService.ACTION_PERMISSION_RESULT
                    putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
                    putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
                    // Forward stream config from launching intent
                    putExtra(ScreenCaptureService.EXTRA_WIDTH, intent.getIntExtra(ScreenCaptureService.EXTRA_WIDTH, 720))
                    putExtra(ScreenCaptureService.EXTRA_HEIGHT, intent.getIntExtra(ScreenCaptureService.EXTRA_HEIGHT, 1280))
                    putExtra(ScreenCaptureService.EXTRA_FPS, intent.getIntExtra(ScreenCaptureService.EXTRA_FPS, 30))
                    putExtra(ScreenCaptureService.EXTRA_WAIT_FOR_OFFER, intent.getBooleanExtra(ScreenCaptureService.EXTRA_WAIT_FOR_OFFER, false))
                }
                startForegroundService(serviceIntent)
            } else {
                Log.e(TAG, "MediaProjection permission denied")
                // Notify cloud of permission denial
                notifyPermissionDenied()
            }
            
            finish()
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }
    
    private fun notifyPermissionDenied() {
        try {
            val service = ReverseConnectionService.getInstance()
            if (service != null) {
                val requestId = WebRtcManager
                    .getInstance(this)
                    .getStreamRequestId()

                val errorMessage = JSONObject().apply {
                    put("method", "stream/error")
                    put("params", JSONObject().apply {
                        put("error", "permission_denied")
                        put("message", "User denied screen capture permission")
                        if (requestId != null) put("sessionId", requestId)
                    })
                }
                service.sendText(errorMessage.toString())
            } else {
                Log.w(TAG, "ReverseConnectionService not available to notify cloud")
            }
            WebRtcManager
                .getInstance(this)
                .setStreamRequestId(null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to notify cloud of permission denial", e)
        }
    }
}
