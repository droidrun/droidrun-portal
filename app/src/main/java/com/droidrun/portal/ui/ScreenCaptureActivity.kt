package com.droidrun.portal.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log
import com.droidrun.portal.service.ReverseConnectionService
import com.droidrun.portal.service.ScreenCaptureService
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
        
        Log.d(TAG, "Requesting MediaProjection permission")
        startActivityForResult(mediaProjectionManager.createScreenCaptureIntent(), REQUEST_CODE_CAPTURE_PERM)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST_CODE_CAPTURE_PERM) {
            Log.d(TAG, "MediaProjection permission result: $resultCode")
            
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
                val errorMessage = JSONObject().apply {
                    put("method", "stream/error")
                    put("params", JSONObject().apply {
                        put("error", "permission_denied")
                        put("message", "User denied screen capture permission")
                    })
                }
                service.sendText(errorMessage.toString())
                Log.d(TAG, "Notified cloud of permission denial")
            } else {
                Log.w(TAG, "ReverseConnectionService not available to notify cloud")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to notify cloud of permission denial", e)
        }
    }
}
