package com.droidrun.portal.ui.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.content.Intent
import android.view.View
import java.util.concurrent.atomic.AtomicBoolean
import android.os.Build
import android.view.WindowInsets
import java.util.concurrent.CopyOnWriteArrayList

class OverlayManager(private val context: Context) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: OverlayView? = null
    private val handler = Handler(Looper.getMainLooper())
    private val elementRects = CopyOnWriteArrayList<ElementInfo>()
    private var isOverlayVisible = false
    private var isDrawingEnabled = true // Flag to temporarily disable drawing
    private var positionCorrectionOffset = 0 // Default correction offset
    private var elementIndexCounter = 0 // Counter to assign indexes to elements
    private val isOverlayReady = AtomicBoolean(false)
    private var onReadyCallback: (() -> Unit)? = null
    
    private var positionOffsetY = 0 // Default offset value

    companion object {
        private const val TAG = "TOPVIEW_OVERLAY"
        private const val OVERLAP_THRESHOLD = 0.5f // Lower overlap threshold for matching
        private const val FALLBACK_OFFSET = 0 // Safe default if detection fails

        // Reasonable bounds for status bar height (in pixels)
        private const val MIN_REASONABLE_OFFSET = -200  // Negative because we shift up
        private const val MAX_REASONABLE_OFFSET = -10   // Minimum status bar size

        // Define a color scheme with 8 visually distinct colors
        private val COLOR_SCHEME = arrayOf(
            Color.rgb(0, 122, 255),    // Blue
            Color.rgb(255, 45, 85),    // Red
            Color.rgb(52, 199, 89),    // Green
            Color.rgb(255, 149, 0),    // Orange
            Color.rgb(175, 82, 222),   // Purple
            Color.rgb(255, 204, 0),    // Yellow
            Color.rgb(90, 200, 250),   // Light Blue
            Color.rgb(88, 86, 214)     // Indigo
        )
    }

    data class ElementInfo(
        val rect: Rect, 
        val type: String, 
        val text: String,
        val depth: Int = 0, // Added depth field to track hierarchy level
        val color: Int = Color.GREEN, // Add color field with default value
        val index: Int = 0 // Index number for identifying the element
    )
    
    // Add method to adjust the vertical offset
    fun setPositionOffsetY(offsetY: Int) {
        this.positionOffsetY = offsetY
        // Redraw existing elements with the new offset
        val existingElements = ArrayList(elementRects)
        elementRects.clear()
        
        // Re-add elements with the updated offset
        for (element in existingElements) {
            val originalRect = Rect(element.rect)
            // Adjust back to original position by removing the old offset
            originalRect.offset(0, -positionOffsetY)
            // Add again with new offset
            addElement(
                rect = originalRect,
                type = element.type,
                text = element.text,
                depth = element.depth,
                color = element.color
            )
        }
        
        refreshOverlay()
    }
    
    // Add getter for the current offset value
    fun getPositionOffsetY(): Int {
        return positionOffsetY
    }

    /**
     * Automatically calculate the status bar height offset.
     * Returns a negative value (to shift overlay upward).
     * Uses multiple detection methods with fallbacks for maximum compatibility.
     */
    fun calculateAutoOffset(): Int {
        // With FLAG_LAYOUT_NO_LIMITS and LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES,
        // the overlay should always be full screen (0,0) to (width, height).
        // So, offset is not needed
        return 0
    }

    private fun getStatusBarHeightFromInsets(): Int {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val metrics = windowManager.currentWindowMetrics
                val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.systemBars()
                )
                -insets.top  // Negative to shift upward
            } else {
                getStatusBarHeightFromResources()  // Fallback for SDK 29 and below
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting insets-based offset: ${e.message}", e)
            getStatusBarHeightFromResources()  // Fallback to resource method
        }
    }

    private fun getStatusBarHeightFromResources(): Int {
        return try {
            val resourceId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
            if (resourceId > 0) {
                val height = context.resources.getDimensionPixelSize(resourceId)
                -height  // Negative to shift upward
            } else {
                Log.w(TAG, "Status bar height resource not found")
                FALLBACK_OFFSET
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting resource-based offset: ${e.message}", e)
            FALLBACK_OFFSET
        }
    }

    fun setOnReadyCallback(callback: () -> Unit) {
        onReadyCallback = callback
        // If already ready, call immediately
        if (isOverlayReady.get()) {
            handler.post(callback)
        }
    }

    fun showOverlay() {
        if (overlayView != null) {
            Log.d(TAG, "Overlay already exists, checking if it's attached")
            try {
                // Check if the view is actually attached
                overlayView?.parent ?: run {
                    Log.w(TAG, "Overlay exists but not attached, recreating")
                    overlayView = null
                    createAndAddOverlay()
                    return
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error checking overlay state: ${e.message}", e)
                overlayView = null
                createAndAddOverlay()
                return
            }
            isOverlayReady.set(true)
            onReadyCallback?.let { handler.post(it) }
            return
        }
        createAndAddOverlay()
    }

    private fun createAndAddOverlay() {
        try {
            Log.d(TAG, "Creating new overlay")
            overlayView = OverlayView(context).apply {
                // Set hardware acceleration
                setLayerType(View.LAYER_TYPE_HARDWARE, null)
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.TOP or Gravity.START

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) 
                params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            
            
            handler.post {
                try {
                    windowManager.addView(overlayView, params)
                    isOverlayVisible = true
                    
                    // Set ready state and notify callback after a short delay to ensure view is laid out
                    handler.postDelayed({
                        if (overlayView?.parent != null) {
                            isOverlayReady.set(true)
                            onReadyCallback?.let { it() }
                        } else {
                            Log.e(TAG, "Overlay not properly attached after delay")
                            // Try to recreate if not attached
                            hideOverlay()
                            showOverlay()
                        }
                    }, 500)
                } catch (e: Exception) {
                    Log.e(TAG, "Error adding overlay: ${e.message}", e)
                    // Clean up on failure
                    overlayView = null
                    isOverlayVisible = false
                    isOverlayReady.set(false)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating overlay: ${e.message}", e)
            // Clean up on failure
            overlayView = null
            isOverlayVisible = false
            isOverlayReady.set(false)
        }
    }

    fun hideOverlay() {
        handler.post {
            try {
                overlayView?.let {
                    windowManager.removeView(it)
                    overlayView = null
                }
                isOverlayVisible = false
                isOverlayReady.set(false)
                Log.d(TAG, "Overlay removed")
            } catch (e: Exception) {
                Log.e(TAG, "Error removing overlay: ${e.message}", e)
            }
        }
    }

    fun clearElements() {
        elementRects.clear()
        elementIndexCounter = 0 // Reset the index counter when clearing elements
        refreshOverlay()
    }

    fun addElement(rect: Rect, type: String, text: String, depth: Int = 0, color: Int = Color.GREEN, index: Int? = null) {
        // Apply position correction to the rectangle
        val correctedRect = correctRectPosition(rect)
        val elementIndex = index ?: elementIndexCounter++
        // Assign a color from the color scheme based on the index
        val colorFromScheme = COLOR_SCHEME[elementIndex % COLOR_SCHEME.size]
        elementRects.add(ElementInfo(correctedRect, type, text, depth, colorFromScheme, elementIndex))
        // Don't refresh on each add to avoid excessive redraws with many elements
    }
    
    // Correct the rectangle position to better match the actual UI element
    private fun correctRectPosition(rect: Rect): Rect {
        val correctedRect = Rect(rect)
        
        // Apply the vertical offset to shift the rectangle upward
        correctedRect.offset(0, positionOffsetY)
        
        return correctedRect
    }

    fun refreshOverlay() {
        handler.post {
            if (overlayView == null) {
                Log.e(TAG, "Cannot refresh overlay - view is null")
                showOverlay()
            }
            overlayView?.invalidate()
        }
    }

    // Get the count of elements in the overlay
    fun getElementCount(): Int {
        return elementRects.size
    }
    
    // Methods to temporarily disable/enable overlay drawing for clean screenshots
    fun setDrawingEnabled(enabled: Boolean) {
        isDrawingEnabled = enabled
        refreshOverlay()
    }
    
    fun isDrawingEnabled(): Boolean {
        return isDrawingEnabled
    }
    
    // Convenience method to temporarily hide overlay for clean screenshots
    fun withOverlayHidden(action: () -> Unit) {
        val wasEnabled = isDrawingEnabled
        try {
            setDrawingEnabled(false)
            // Small delay to ensure the overlay is redrawn without elements
            handler.postDelayed({
                action()
                setDrawingEnabled(wasEnabled)
            }, 50)
        } catch (e: Exception) {
            // Ensure we restore state even if action fails
            setDrawingEnabled(wasEnabled)
            throw e
        }
    }

    inner class OverlayView(context: Context) : FrameLayout(context) {
        private val boxPaint = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f  // Thinner border as requested
            isAntiAlias = true
            // Enable hardware acceleration features
            flags = Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG
        }
        
        private val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = 32f  // Increased text size for better visibility
            isAntiAlias = true
            // Enable hardware acceleration features
            flags = Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG
        }
        
        private val textBackgroundPaint = Paint().apply {
            // Color will be set dynamically to match the border color
            style = Paint.Style.FILL
            // Enable hardware acceleration features
            flags = Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG
        }

        init {
            setWillNotDraw(false)
            setBackgroundColor(Color.TRANSPARENT)
            // Enable hardware acceleration
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
        }

        override fun onDraw(canvas: Canvas) {
            try {
                if (canvas == null) {
                    Log.e(TAG, "Canvas is null in onDraw")
                    return
                }

                if (!isOverlayVisible || !isDrawingEnabled) {
                    if (!isDrawingEnabled) {
                        Log.d(TAG, "Overlay drawing disabled, skipping draw")
                    } else {
                        Log.d(TAG, "Overlay not visible, skipping draw")
                    }
                    return
                }

                super.onDraw(canvas)
                
                val startTime = System.currentTimeMillis()

                if (elementRects.isEmpty()) {
                    if (isDebugging()) {
                        drawDebugRect(canvas)
                    }
                    return
                }
                
                // Create a local copy to prevent concurrent modification
                val elementsToDraw = elementRects

                // Sort elements by depth for drawing order
                val sortedElements = elementsToDraw.sortedBy { it.depth }
                
                for (elementInfo in sortedElements) {
                    drawElement(canvas, elementInfo)
                }

                val drawTime = System.currentTimeMillis() - startTime
            } catch (e: Exception) {
                Log.e(TAG, "Error in onDraw: ${e.message}", e)
            }
        }

        private fun drawElement(canvas: Canvas, elementInfo: ElementInfo) {
            try {
                // Ensure the rectangle is valid
                if (elementInfo.rect.width() <= 0 || elementInfo.rect.height() <= 0) {
                    Log.w(TAG, "Invalid rectangle dimensions for element ${elementInfo.index}")
                    return
                }

                // IMPORTANT: Set the color for this specific element 
                val elementColor = elementInfo.color
                
                // Ensure color has full alpha for visibility
                val colorWithAlpha = Color.argb(
                    255,
                    Color.red(elementColor),
                    Color.green(elementColor),
                    Color.blue(elementColor)
                )
                
                boxPaint.color = colorWithAlpha
                
                // Set the background color to match the border color with some transparency
                textBackgroundPaint.color = Color.argb(
                    200, // Semi-transparent
                    Color.red(elementColor),
                    Color.green(elementColor),
                    Color.blue(elementColor)
                )
                
                // Draw the rectangle with the specified color
                canvas.drawRect(elementInfo.rect, boxPaint)
                
                // Draw the index number in the top-right corner
                val displayText = "${elementInfo.index}"
                val textWidth = textPaint.measureText(displayText)
                val textHeight = 36f  // Larger text height to match increased text size
                
                // Position for top-right corner with small padding
                val textX = elementInfo.rect.right - textWidth - 4f  // 4px padding from right edge
                val textY = elementInfo.rect.top + textHeight  // Position text at top with some padding
                
                // Calculate background rectangle for the text
                val backgroundPadding = 4f
                val backgroundRect = Rect(
                    (textX - backgroundPadding).toInt(),
                    (textY - textHeight).toInt(),
                    (textX + textWidth + backgroundPadding).toInt(),
                    (textY + backgroundPadding).toInt()
                )
                
                // Draw background and text
                canvas.drawRect(backgroundRect, textBackgroundPaint)
                canvas.drawText(
                    displayText,
                    textX,
                    textY - backgroundPadding,
                    textPaint
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error drawing element ${elementInfo.index}: ${e.message}", e)
            }
        }

        private fun drawDebugRect(canvas: Canvas) {
            try {
                val screenWidth = width
                val screenHeight = height
                val testRect = Rect(
                    screenWidth / 4,
                    screenHeight / 4,
                    (screenWidth * 3) / 4,
                    (screenHeight * 3) / 4
                )
                boxPaint.color = Color.GREEN
                canvas.drawRect(testRect, boxPaint)
                Log.d(TAG, "Drew test rectangle at $testRect")
            } catch (e: Exception) {
                Log.e(TAG, "Error drawing debug rectangle: ${e.message}", e)
            }
        }
        
        private fun isDebugging(): Boolean {
            return false // Set to true to show test rectangle
        }
    }
} 