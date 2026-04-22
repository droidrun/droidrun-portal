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
 * When an existing MediaProjection capture is already active, delegates to
 * [WebRtcManager.captureStreamFrame] which taps the current VideoTrack — a fresh
 * MediaProjection cannot run concurrently with the active capture session.
 */
class MediaProjectionScreenshotter private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val projectionManager =
        appContext.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private val lock = Any()
    private var pending: PendingCapture? = null
    private var cachedPermission: CachedPermission? = null

    private data class PendingCapture(
        val future: CompletableFuture<String>,
        val width: Int,
        val height: Int,
        val density: Int,
        val timeoutRunnable: Runnable,
        var usedSharedCaptureSession: Boolean = false,
    )

    private data class CachedPermission(
        val resultCode: Int,
        val data: Intent,
    )

    fun capture(): CompletableFuture<String> {
        val webRtc = WebRtcManager.getInstance(appContext)
        val future = CompletableFuture<String>()
        val metrics = resolveDisplayMetrics()
        val timeoutRunnable = Runnable {
            completePending("error: screenshot_timeout")
        }
        val pendingCapture: PendingCapture

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
            pendingCapture = pending!!
        }

        mainHandler.postDelayed(timeoutRunnable, CAPTURE_TIMEOUT_MS)

        if (webRtc.hasReusableCaptureFrameSource()) {
            Log.d(TAG, "capture: reusing active shared capture frame")
            pendingCapture.usedSharedCaptureSession = true
            completeFromReusableCapture()
            return future
        }

        val cachedGrant = synchronized(lock) {
            cachedPermission?.let { CachedPermission(it.resultCode, Intent(it.data)) }
        }
        if (cachedGrant != null) {
            Log.d(TAG, "capture: reusing cached MediaProjection permission")
            startSharedCapture(
                p = pendingCapture,
                resultCode = cachedGrant.resultCode,
                data = cachedGrant.data,
                allowPromptRetry = true,
            )
            return future
        }

        launchPermissionPrompt()

        return future
    }

    fun onPermissionResult(resultCode: Int, data: Intent?) {
        val p = synchronized(lock) { pending }
        if (p == null) {
            Log.w(TAG, "onPermissionResult with no pending capture")
            return
        }
        if (resultCode != Activity.RESULT_OK || data == null) {
            clearCachedPermission()
            completePending("error: permission_denied")
            return
        }
        cachePermission(resultCode, data)
        if (WebRtcManager.getInstance(appContext).hasReusableCaptureFrameSource()) {
            p.usedSharedCaptureSession = true
            completeFromReusableCapture()
            return
        }
        startSharedCapture(p, resultCode, Intent(data))
    }

    fun onSharedCaptureReady() {
        completeFromReusableCapture()
    }

    fun onSharedCaptureFailed(message: String) {
        val p = synchronized(lock) { pending }
        if (p == null) {
            Log.w(TAG, "onSharedCaptureFailed with no pending capture")
            return
        }
        val cachedGrant = synchronized(lock) {
            cachedPermission?.let { CachedPermission(it.resultCode, Intent(it.data)) }
        }
        if (cachedGrant != null) {
            Log.w(TAG, "Shared capture bootstrap failed ($message); falling back to raw projection capture")
            p.usedSharedCaptureSession = false
            runCapture(
                p = p,
                resultCode = cachedGrant.resultCode,
                data = cachedGrant.data,
                allowPromptRetry = true,
            )
            return
        }
        completePending("error: $message")
    }

    private fun startSharedCapture(
        p: PendingCapture,
        resultCode: Int,
        data: Intent,
        allowPromptRetry: Boolean = false,
    ) {
        p.usedSharedCaptureSession = true
        try {
            val intent = Intent(appContext, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_PERMISSION_RESULT
                putExtra(ScreenCaptureService.EXTRA_CAPTURE_MODE, ScreenCaptureService.CAPTURE_MODE_SCREENSHOT)
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
                putExtra(ScreenCaptureService.EXTRA_WIDTH, p.width)
                putExtra(ScreenCaptureService.EXTRA_HEIGHT, p.height)
                putExtra(ScreenCaptureService.EXTRA_FPS, SCREENSHOT_CAPTURE_FPS)
            }
            appContext.startForegroundService(intent)
            Log.d(TAG, "capture: starting shared capture owner")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start shared capture owner", e)
            p.usedSharedCaptureSession = false
            runCapture(
                p = p,
                resultCode = resultCode,
                data = data,
                allowPromptRetry = allowPromptRetry,
            )
        }
    }

    private fun completeFromReusableCapture() {
        val p = synchronized(lock) { pending } ?: return
        WebRtcManager.getInstance(appContext).captureStreamFrame().whenComplete { result, error ->
            if (synchronized(lock) { pending } !== p) {
                return@whenComplete
            }
            val value =
                when {
                    error != null -> "error: ${error.message}"
                    result != null -> result
                    else -> "error: empty_result"
                }
            completePending(value)
        }
    }

    private fun runCapture(
        p: PendingCapture,
        resultCode: Int,
        data: Intent,
        allowPromptRetry: Boolean = false,
    ) {
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
                    if (allowPromptRetry && requestFreshPermissionPrompt("projection_null")) {
                        cleanup()
                        return@post
                    }
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
                if (allowPromptRetry && requestFreshPermissionPrompt(e.message ?: "projection_error")) {
                    cleanup()
                    return@post
                }
                if (done.compareAndSet(false, true)) {
                    completePending("error: ${e.message}")
                    cleanup()
                }
            }
        }
    }

    private fun launchPermissionPrompt(): Boolean {
        return try {
            val intent = Intent(appContext, ScreenCaptureActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                putExtra(ScreenCaptureActivity.EXTRA_MODE, ScreenCaptureActivity.MODE_SCREENSHOT)
            }
            appContext.startActivity(intent)
            Log.d(TAG, "capture: requesting fresh MediaProjection permission")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch ScreenCaptureActivity", e)
            completePending("error: ${e.message}")
            false
        }
    }

    private fun requestFreshPermissionPrompt(reason: String): Boolean {
        clearCachedPermission()
        Log.w(TAG, "Cached MediaProjection permission was not reusable ($reason); prompting again")
        return launchPermissionPrompt()
    }

    private fun cachePermission(resultCode: Int, data: Intent) {
        synchronized(lock) {
            cachedPermission = CachedPermission(resultCode, Intent(data))
        }
    }

    private fun clearCachedPermission() {
        synchronized(lock) {
            cachedPermission = null
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
            WebRtcManager
                .getInstance(appContext)
                .onSharedCaptureScreenshotCompleted(p.usedSharedCaptureSession)
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
        private const val SCREENSHOT_CAPTURE_FPS = 10

        @Volatile
        private var instance: MediaProjectionScreenshotter? = null

        fun getInstance(context: Context): MediaProjectionScreenshotter {
            return instance ?: synchronized(MediaProjectionScreenshotter::class.java) {
                instance ?: MediaProjectionScreenshotter(context).also { instance = it }
            }
        }
    }
}
