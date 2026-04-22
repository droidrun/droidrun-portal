package com.mobilerun.portal.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.util.Base64
import android.util.Log
import android.view.Display
import androidx.annotation.RequiresApi
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

@RequiresApi(Build.VERSION_CODES.R)
internal object AccessibilityScreenshotApi30 {
    fun takeScreenshot(
        service: AccessibilityService,
        tag: String,
        onSuccess: (String) -> Unit,
        onFailure: (String) -> Unit,
    ) {
        val executor = Executors.newSingleThreadExecutor()
        try {
            service.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                executor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshotResult: AccessibilityService.ScreenshotResult) {
                        try {
                            val bitmap = Bitmap.wrapHardwareBuffer(
                                screenshotResult.hardwareBuffer,
                                screenshotResult.colorSpace,
                            )

                            if (bitmap == null) {
                                Log.e(tag, "Failed to create bitmap from hardware buffer")
                                screenshotResult.hardwareBuffer.close()
                                onFailure("Failed to create bitmap from screenshot data")
                                return
                            }

                            val output = ByteArrayOutputStream()
                            val compressed = bitmap.compress(
                                Bitmap.CompressFormat.PNG,
                                100,
                                output,
                            )
                            if (!compressed) {
                                Log.e(tag, "Failed to compress bitmap to PNG")
                                bitmap.recycle()
                                screenshotResult.hardwareBuffer.close()
                                output.close()
                                onFailure("Failed to compress screenshot to PNG format")
                                return
                            }

                            val bytes = output.toByteArray()
                            val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                            bitmap.recycle()
                            screenshotResult.hardwareBuffer.close()
                            output.close()
                            Log.d(
                                tag,
                                "Screenshot captured successfully, size: ${bytes.size} bytes"
                            )
                            onSuccess(base64)
                        } catch (e: Exception) {
                            Log.e(tag, "Error processing screenshot", e)
                            try {
                                screenshotResult.hardwareBuffer.close()
                            } catch (closeException: Exception) {
                                Log.e(tag, "Error closing hardware buffer", closeException)
                            }
                            onFailure("Failed to process screenshot: ${e.message}")
                        } finally {
                            executor.shutdown()
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        val message = when (errorCode) {
                            AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR ->
                                "Internal error occurred"

                            AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT ->
                                "Screenshot interval too short"

                            AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY ->
                                "Invalid display"

                            AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS ->
                                "No accessibility access"

                            AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW ->
                                "Secure window cannot be captured"

                            else -> "Unknown error (code: $errorCode)"
                        }
                        Log.e(tag, "Screenshot failed: $message")
                        executor.shutdown()
                        onFailure("Screenshot failed: $message")
                    }
                },
            )
        } catch (e: Exception) {
            executor.shutdown()
            throw e
        }
    }
}
