package com.mobilerun.portal.service

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Base64
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.mobilerun.portal.streaming.WebRtcManager
import com.mobilerun.portal.ui.ScreenCaptureActivity
import java.io.ByteArrayOutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Single-frame MediaProjection-based screenshot path, used on API 26-29 where
 * AccessibilityService.takeScreenshot() (API 30+) is unavailable.
 *
 * When a WebRTC stream is already active, delegates to
 * [WebRtcManager.captureStreamFrame] which taps the live VideoTrack — a fresh
 * MediaProjection cannot run concurrently with the streamer's projection.
 */
class MediaProjectionScreenshotter private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val projectionManager =
        appContext.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private val lock = Any()
    private var pending: PendingCapture? = null

    private data class PendingCapture(
        val future: CompletableFuture<String>,
        val width: Int,
        val height: Int,
        val density: Int,
        val timeoutRunnable: Runnable,
    )

    fun capture(): CompletableFuture<String> {
        val webRtc = WebRtcManager.getInstance(appContext)
        if (webRtc.isStreamActive()) {
            return webRtc.captureStreamFrame()
        }

        val future = CompletableFuture<String>()
        val metrics = resolveDisplayMetrics()
        val timeoutRunnable = Runnable {
            completePending("error: screenshot_timeout")
        }

        synchronized(lock) {
            if (pending != null) {
                future.complete("error: screenshot_in_flight")
                return future
            }
            pending = PendingCapture(
                future = future,
                width = metrics.widthPixels,
                height = metrics.heightPixels,
                density = metrics.densityDpi,
                timeoutRunnable = timeoutRunnable,
            )
        }

        mainHandler.postDelayed(timeoutRunnable, CAPTURE_TIMEOUT_MS)

        try {
            val intent = Intent(appContext, ScreenCaptureActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                putExtra(ScreenCaptureActivity.EXTRA_MODE, ScreenCaptureActivity.MODE_SCREENSHOT)
            }
            appContext.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch ScreenCaptureActivity", e)
            completePending("error: ${e.message}")
        }

        return future
    }

    fun onPermissionResult(resultCode: Int, data: Intent?) {
        val p = synchronized(lock) { pending }
        if (p == null) {
            Log.w(TAG, "onPermissionResult with no pending capture")
            return
        }
        if (resultCode != Activity.RESULT_OK || data == null) {
            completePending("error: permission_denied")
            return
        }
        runCapture(p, resultCode, data)
    }

    private fun runCapture(p: PendingCapture, resultCode: Int, data: Intent) {
        val thread = HandlerThread("MediaProjectionScreenshot")
        thread.start()
        val handler = Handler(thread.looper)

        handler.post {
            var projection: MediaProjection? = null
            var virtualDisplay: VirtualDisplay? = null
            var imageReader: ImageReader? = null
            val done = AtomicBoolean(false)

            fun cleanup() {
                try { virtualDisplay?.release() } catch (_: Exception) {}
                try { imageReader?.close() } catch (_: Exception) {}
                try { projection?.stop() } catch (_: Exception) {}
                thread.quitSafely()
            }

            try {
                projection = projectionManager.getMediaProjection(resultCode, data)
                if (projection == null) {
                    if (done.compareAndSet(false, true)) {
                        completePending("error: projection_null")
                        cleanup()
                    }
                    return@post
                }

                projection.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        if (done.compareAndSet(false, true)) {
                            completePending("error: projection_stopped")
                            cleanup()
                        }
                    }
                }, handler)

                imageReader = ImageReader.newInstance(
                    p.width, p.height, PixelFormat.RGBA_8888, 2
                )
                imageReader.setOnImageAvailableListener({ reader ->
                    if (!done.compareAndSet(false, true)) return@setOnImageAvailableListener
                    var image: Image? = null
                    try {
                        image = reader.acquireLatestImage()
                        if (image == null) {
                            completePending("error: no_image")
                            return@setOnImageAvailableListener
                        }
                        val result = encodeImage(image, p.width, p.height)
                        completePending(result)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error capturing frame", e)
                        completePending("error: ${e.message}")
                    } finally {
                        try { image?.close() } catch (_: Exception) {}
                        cleanup()
                    }
                }, handler)

                virtualDisplay = projection.createVirtualDisplay(
                    "portal-screenshot",
                    p.width,
                    p.height,
                    p.density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader.surface,
                    null,
                    handler,
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error starting projection capture", e)
                if (done.compareAndSet(false, true)) {
                    completePending("error: ${e.message}")
                    cleanup()
                }
            }
        }
    }

    private fun encodeImage(image: Image, targetWidth: Int, targetHeight: Int): String {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * targetWidth
        val paddedWidth = targetWidth + if (pixelStride > 0) rowPadding / pixelStride else 0

        val raw = Bitmap.createBitmap(paddedWidth, targetHeight, Bitmap.Config.ARGB_8888)
        raw.copyPixelsFromBuffer(buffer)

        val cropped = if (rowPadding == 0) raw
        else Bitmap.createBitmap(raw, 0, 0, targetWidth, targetHeight)

        val bos = ByteArrayOutputStream()
        val ok = cropped.compress(Bitmap.CompressFormat.PNG, 100, bos)
        if (cropped !== raw) raw.recycle()
        cropped.recycle()

        if (!ok) {
            bos.close()
            return "error: png_encode_failed"
        }
        val bytes = bos.toByteArray()
        bos.close()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun completePending(result: String) {
        val p = synchronized(lock) {
            val current = pending
            pending = null
            current
        }
        if (p != null) {
            mainHandler.removeCallbacks(p.timeoutRunnable)
            p.future.complete(result)
        }
    }

    private fun resolveDisplayMetrics(): DisplayMetrics {
        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        return metrics
    }

    companion object {
        private const val TAG = "MediaProjScreenshot"
        private const val CAPTURE_TIMEOUT_MS = 15_000L

        @Volatile
        private var instance: MediaProjectionScreenshotter? = null

        fun getInstance(context: Context): MediaProjectionScreenshotter {
            return instance ?: synchronized(MediaProjectionScreenshotter::class.java) {
                instance ?: MediaProjectionScreenshotter(context).also { instance = it }
            }
        }
    }
}
