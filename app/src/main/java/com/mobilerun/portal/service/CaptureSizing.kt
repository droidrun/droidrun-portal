package com.mobilerun.portal.service

import android.content.Context
import android.os.Build
import android.content.res.Resources
import android.util.Log

/**
 * Derives the MediaProjection capture size (legacy 720x1280 default, or an
 * aspect-fit into that box) from the device's real display metrics.
 *
 * Kept as a neutral object with no dependency on [com.mobilerun.portal.api.ApiHandler]
 * so that [ScreenCaptureService] (which needs to re-derive the size at
 * permission-grant time) does not have to import the api package — that
 * import used to create an api↔service package cycle.
 */
object CaptureSizing {
    private const val TAG = "CaptureSizing"

    const val DEFAULT_CAPTURE_WIDTH = 720
    const val DEFAULT_CAPTURE_HEIGHT = 1280
    const val CAPTURE_WIDTH_MIN = 144
    const val CAPTURE_WIDTH_MAX = 1920
    const val CAPTURE_HEIGHT_MIN = 256
    const val CAPTURE_HEIGHT_MAX = 3840

    // Aspect-fits the real screen into the legacy 720x1280 default capture box
    // (never upscaled) so MediaProjection doesn't bake pillarbox/letterbox bars
    // into the frame. Dimensions are rounded down to even and clamped to the
    // existing per-field bounds. If clamping would actually change either
    // dimension (i.e. the fitted size falls outside the legacy bounds), the
    // fit is rejected outright rather than returning a distorted aspect ratio
    // — callers fall back to the legacy 720x1280 default (Fix B in the
    // injection path handles any residual mismatch).
    fun fitCaptureSizeToScreen(screenWidth: Int, screenHeight: Int): Pair<Int, Int>? {
        if (screenWidth <= 0 || screenHeight <= 0) return null

        val candidateWidthScale = DEFAULT_CAPTURE_WIDTH.toDouble() / screenWidth
        val candidateHeightScale = DEFAULT_CAPTURE_HEIGHT.toDouble() / screenHeight
        // No-upscale guard: never scale up past 1.0.
        val scale = minOf(candidateWidthScale, candidateHeightScale, 1.0)

        val width: Int
        val height: Int
        if (scale >= 1.0) {
            // Screen already fits inside the legacy box on both axes —
            // keep the screen's own size (floored to even) rather than
            // stretching either axis out to a box value that doesn't
            // apply here.
            width = floorToEven(screenWidth.toDouble())
            height = floorToEven(screenHeight.toDouble())
        } else if (candidateWidthScale <= candidateHeightScale) {
            // Width is the limiting axis: assign it the box value
            // EXACTLY rather than deriving it via scale*screenWidth,
            // which can undershoot by a fraction of a pixel and
            // floor-even one lower than intended (e.g. 1080x2139 →
            // 646x1278 instead of 646x1280). Only the other axis is
            // floor-even'd from the float/double math.
            width = DEFAULT_CAPTURE_WIDTH
            height = floorToEven(screenHeight * scale)
        } else {
            height = DEFAULT_CAPTURE_HEIGHT
            width = floorToEven(screenWidth * scale)
        }
        if (width <= 0 || height <= 0) return null

        val clampedWidth = width.coerceIn(CAPTURE_WIDTH_MIN, CAPTURE_WIDTH_MAX)
        val clampedHeight = height.coerceIn(CAPTURE_HEIGHT_MIN, CAPTURE_HEIGHT_MAX)
        if (clampedWidth != width || clampedHeight != height) return null

        return Pair(clampedWidth, clampedHeight)
    }

    private fun floorToEven(value: Double): Int {
        val floored = kotlin.math.floor(value).toInt()
        return if (floored % 2 != 0) floored - 1 else floored
    }

    /**
     * Reads the current display bounds directly from a [Context], with no
     * dependency on an ApiHandler instance. Exposed so callers that only
     * have a Context at hand (e.g. ScreenCaptureService re-deriving the
     * capture size at permission-grant time, after a possible rotation)
     * can reuse the exact same display-metrics logic instead of
     * duplicating it.
     */
    fun readDisplaySize(context: Context): Pair<Int, Int>? {
        return try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val bounds = wm?.maximumWindowMetrics?.bounds
                Pair(
                    bounds?.width() ?: Resources.getSystem().displayMetrics.widthPixels,
                    bounds?.height() ?: Resources.getSystem().displayMetrics.heightPixels,
                )
            } else {
                val metrics = android.util.DisplayMetrics()
                @Suppress("DEPRECATION")
                wm?.defaultDisplay?.getRealMetrics(metrics)
                Pair(metrics.widthPixels, metrics.heightPixels)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read display size for default capture size", e)
            null
        }
    }

    /**
     * The "auto" default capture size for the screen currently reported by
     * [context]: an aspect-fit into the legacy 720x1280 box, falling back
     * to that legacy default outright if the display can't be read or
     * doesn't fit within bounds. Used both for the pre-permission-prompt
     * default and for re-deriving the size at permission-grant time (Fix
     * for stale-orientation dims baked in while the prompt was open).
     */
    fun deriveAutoCaptureSize(context: Context): Pair<Int, Int> {
        val screenSize = readDisplaySize(context)
        val fitted = screenSize?.let { (w, h) -> fitCaptureSizeToScreen(w, h) }
        return fitted ?: Pair(DEFAULT_CAPTURE_WIDTH, DEFAULT_CAPTURE_HEIGHT)
    }
}
