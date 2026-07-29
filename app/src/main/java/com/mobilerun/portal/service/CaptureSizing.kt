package com.mobilerun.portal.service

import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager

/**
 * Derives an aspect-correct capture size when a stream request omits both
 * dimensions.
 *
 * The legacy 720x1280 size remains the bounding box and fallback. Explicit
 * stream dimensions do not pass through this helper.
 */
internal object CaptureSizing {
    private const val TAG = "CaptureSizing"

    const val DEFAULT_CAPTURE_WIDTH = 720
    const val DEFAULT_CAPTURE_HEIGHT = 1280
    const val CAPTURE_WIDTH_MIN = 144
    const val CAPTURE_WIDTH_MAX = 1920
    const val CAPTURE_HEIGHT_MIN = 256
    const val CAPTURE_HEIGHT_MAX = 3840

    private val legacyCaptureSize =
        Pair(DEFAULT_CAPTURE_WIDTH, DEFAULT_CAPTURE_HEIGHT)

    /**
     * Aspect-fits the screen into an arbitrary capture box without upscaling.
     *
     * Cross-products and integer division avoid floating-point underflow at
     * exact aspect ratios. A result outside the existing stream bounds is
     * rejected wholesale instead of clamping one axis and distorting it. The
     * result never exceeds the box in either dimension.
     */
    fun fitCaptureSizeToScreen(
        screenWidth: Int,
        screenHeight: Int,
        boxWidth: Int = DEFAULT_CAPTURE_WIDTH,
        boxHeight: Int = DEFAULT_CAPTURE_HEIGHT,
    ): Pair<Int, Int>? {
        if (screenWidth <= 0 || screenHeight <= 0 || boxWidth <= 0 || boxHeight <= 0) return null

        val fittedWidth: Long
        val fittedHeight: Long
        if (screenWidth <= boxWidth && screenHeight <= boxHeight) {
            fittedWidth = floorToEven(screenWidth.toLong())
            fittedHeight = floorToEven(screenHeight.toLong())
        } else {
            val widthIsLimiting =
                boxWidth.toLong() * screenHeight <= boxHeight.toLong() * screenWidth

            if (widthIsLimiting) {
                fittedWidth = floorToEven(boxWidth.toLong())
                fittedHeight =
                    floorToEven(
                        screenHeight.toLong() * boxWidth / screenWidth,
                    )
            } else {
                fittedWidth =
                    floorToEven(
                        screenWidth.toLong() * boxHeight / screenHeight,
                    )
                fittedHeight = floorToEven(boxHeight.toLong())
            }
        }

        if (
            fittedWidth !in CAPTURE_WIDTH_MIN.toLong()..CAPTURE_WIDTH_MAX.toLong() ||
            fittedHeight !in CAPTURE_HEIGHT_MIN.toLong()..CAPTURE_HEIGHT_MAX.toLong()
        ) {
            return null
        }

        return Pair(fittedWidth.toInt(), fittedHeight.toInt())
    }

    fun deriveAutoCaptureSize(
        screenWidth: Int,
        screenHeight: Int,
    ): Pair<Int, Int> =
        fitCaptureSizeToScreen(screenWidth, screenHeight) ?: legacyCaptureSize

    /**
     * Resolves the current screen size, falling back atomically to the legacy
     * capture size if display lookup or aspect fitting fails.
     */
    fun deriveAutoCaptureSize(context: Context): Pair<Int, Int> {
        val screenSize = readDisplaySize(context) ?: return legacyCaptureSize
        return deriveAutoCaptureSize(screenSize.first, screenSize.second)
    }

    /**
     * Resolves the current screen size aspect-fitted into [maxWidth]x[maxHeight].
     *
     * Falls back to the default-box auto size if display lookup fails or the
     * fit is rejected, never returning a distorted or out-of-bounds size and
     * never falling back to the raw cap values. An unusable cap (e.g. below
     * the supported capture minima) is treated as absent, so the fallback may
     * exceed the requested box — stream start must not fail on a bad cap.
     */
    fun deriveCapCaptureSize(
        context: Context,
        maxWidth: Int,
        maxHeight: Int,
    ): Pair<Int, Int> {
        val screenSize = readDisplaySize(context) ?: return deriveAutoCaptureSize(context)
        return fitCaptureSizeToScreen(screenSize.first, screenSize.second, maxWidth, maxHeight)
            ?: deriveAutoCaptureSize(context)
    }

    fun readDisplaySize(context: Context): Pair<Int, Int>? {
        return try {
            val windowManager =
                context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                    ?: return null

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = windowManager.maximumWindowMetrics.bounds
                Pair(bounds.width(), bounds.height())
            } else {
                val metrics = DisplayMetrics()
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.getRealMetrics(metrics)
                Pair(metrics.widthPixels, metrics.heightPixels)
            }
        } catch (error: Exception) {
            Log.w(TAG, "Failed to read display size for automatic capture sizing", error)
            null
        }
    }

    private fun floorToEven(value: Long): Long = value - (value % 2L)
}
