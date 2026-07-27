package com.mobilerun.portal.streaming

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.res.Resources
import android.graphics.Path
import android.view.KeyEvent
import com.mobilerun.portal.input.MobilerunKeyboardIME
import com.mobilerun.portal.service.MobilerunAccessibilityService
import com.mobilerun.portal.service.GestureController
import org.webrtc.DataChannel
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ScrcpyControlChannel : DataChannel.Observer {
    companion object {
        private const val TYPE_INJECT_KEYCODE = 0
        private const val TYPE_INJECT_TEXT = 1
        private const val TYPE_INJECT_TOUCH_EVENT = 2
        private const val TYPE_INJECT_SCROLL_EVENT = 3
        private const val TYPE_BACK_OR_SCREEN_ON = 4
        private const val TYPE_EXPAND_NOTIFICATION_PANEL = 5
        private const val TYPE_EXPAND_SETTINGS_PANEL = 6
        private const val TYPE_COLLAPSE_PANELS = 7
        private const val TYPE_SET_CLIPBOARD = 9

        private const val ACTION_DOWN = 0
        private const val ACTION_UP = 1
        private const val ACTION_MOVE = 2

        private const val MIN_GESTURE_DURATION_MS = 50L
        private const val MAX_GESTURE_DURATION_MS = 5000L

        // MediaProjection captures the full display with a centered, uniform
        // aspect-fit into the video canvas (no inset/cutout math). A point that
        // lands in the resulting pillarbox/letterbox bars has no corresponding
        // screen position and must not be injected as a tap.
        fun mapFrameToScreen(
            x: Int,
            y: Int,
            videoW: Int,
            videoH: Int,
            screenW: Int,
            screenH: Int,
        ): Pair<Float, Float>? {
            if (videoW <= 0 || videoH <= 0 || screenW <= 0 || screenH <= 0) return null

            val scaleX = videoW.toDouble() / screenW
            val scaleY = videoH.toDouble() / screenH
            val scale = minOf(scaleX, scaleY)

            // The limiting axis (whichever of scaleX/scaleY is the min) fits
            // the video canvas edge-to-edge, so its offset must be EXACTLY
            // 0.0 — not the result of a floating-point subtraction, which can
            // leave a residual of a few ten-thousandths of a pixel (e.g.
            // video=720x1280 vs screen=1080x2408 previously computed
            // offsetY≈0.000061 instead of 0) that's enough to push a
            // legitimate edge tap (y=0) outside the bounds check below and
            // have it misclassified as a pillarbox/letterbox bar point.
            val offsetX: Double
            val offsetY: Double
            if (scaleX <= scaleY) {
                offsetX = 0.0
                offsetY = (videoH - screenH * scale) / 2.0
            } else {
                offsetX = (videoW - screenW * scale) / 2.0
                offsetY = 0.0
            }

            val frameX = x.toDouble()
            val frameY = y.toDouble()
            if (frameX < offsetX || frameX > videoW - offsetX ||
                frameY < offsetY || frameY > videoH - offsetY
            ) {
                return null
            }

            val screenX = (frameX - offsetX) / scale
            val screenY = (frameY - offsetY) / scale

            return Pair(
                screenX.toFloat().coerceIn(0f, (screenW - 1).toFloat()),
                screenY.toFloat().coerceIn(0f, (screenH - 1).toFloat()),
            )
        }
    }

    private val touchPath = mutableListOf<Pair<Float, Float>>()
    private var touchStartTime = 0L
    private var gestureActive = false

    // The pointer that owns the currently in-flight gesture. Multi-touch
    // injection stays UNSUPPORTED (dispatchPath below drives a single
    // GestureDescription stroke), but a second pointer must not corrupt the
    // active pointer's state: while a gesture is active, DOWN/MOVE/UP from
    // any other pointer id is ignored outright rather than clearing
    // touchPath or cancelling a valid gesture.
    private var activePointerId: Long? = null

    override fun onBufferedAmountChange(previousAmount: Long) {}

    override fun onStateChange() {}

    override fun onMessage(buffer: DataChannel.Buffer) {
        val data = ByteArray(buffer.data.remaining())
        buffer.data.get(data)
        handleMessage(data)
    }

    private fun handleMessage(data: ByteArray) {
        if (data.isEmpty()) return

        val type = data[0].toInt() and 0xFF
        when (type) {
            TYPE_INJECT_TOUCH_EVENT -> handleTouch(data)
            TYPE_INJECT_SCROLL_EVENT -> handleScroll(data)
            TYPE_BACK_OR_SCREEN_ON -> handleBack(data)
            TYPE_INJECT_TEXT -> handleText(data)
            TYPE_INJECT_KEYCODE -> handleKeycode(data)
            TYPE_SET_CLIPBOARD -> handleSetClipboard(data)
            TYPE_EXPAND_NOTIFICATION_PANEL -> expandNotificationPanel()
            TYPE_EXPAND_SETTINGS_PANEL -> expandSettingsPanel()
            TYPE_COLLAPSE_PANELS -> collapsePanels()
        }
    }

    private fun handleTouch(data: ByteArray) {
        if (data.size < 32) return

        val buffer = ByteBuffer.wrap(data)

        buffer.order(ByteOrder.BIG_ENDIAN)
        buffer.get()
        val action = buffer.get().toInt() and 0xFF
        val pointerId = buffer.long

        buffer.order(ByteOrder.LITTLE_ENDIAN)
        val x = buffer.int
        val y = buffer.int
        val videoWidth = buffer.short.toInt() and 0xFFFF
        val videoHeight = buffer.short.toInt() and 0xFFFF
        buffer.short

        // A second pointer while a gesture is active is dropped wholesale —
        // its DOWN would otherwise clear touchPath out from under the first
        // pointer, and its bar-DOWN would otherwise cancel a valid running
        // gesture. The active pointer's own events fall through untouched.
        if (gestureActive && pointerId != activePointerId) {
            return
        }

        val mapped = scaleCoordinates(x, y, videoWidth, videoHeight)

        when (action) {
            ACTION_DOWN -> {
                touchPath.clear()
                if (mapped == null) {
                    gestureActive = false
                    activePointerId = null
                    return
                }
                gestureActive = true
                activePointerId = pointerId
                touchPath.add(mapped)
                touchStartTime = System.currentTimeMillis()
            }
            ACTION_MOVE -> {
                if (!gestureActive) return
                // A move landing in the bars is dropped, not dispatched as a
                // partial gesture: keep the gesture alive and skip the point.
                if (mapped == null) return
                touchPath.add(mapped)
            }
            ACTION_UP -> {
                if (!gestureActive) {
                    touchPath.clear()
                    return
                }
                // UP in the bars intentionally releases at the last valid point —
                // mirrors the web client (which sends UP at the last inside
                // position) and scrcpy; cancelling here would swallow
                // drag-to-edge gestures.
                (mapped ?: touchPath.lastOrNull())?.let { touchPath.add(it) }
                val duration = (System.currentTimeMillis() - touchStartTime)
                    .coerceIn(MIN_GESTURE_DURATION_MS, MAX_GESTURE_DURATION_MS)
                dispatchPath(touchPath.toList(), duration)
                touchPath.clear()
                gestureActive = false
                activePointerId = null
            }
        }
    }

    private fun handleScroll(data: ByteArray) {
        if (data.size < 21) return

        val buffer = ByteBuffer.wrap(data)
        buffer.order(ByteOrder.LITTLE_ENDIAN)

        buffer.get()
        val x = buffer.int
        val y = buffer.int
        val videoWidth = buffer.short.toInt() and 0xFFFF
        val videoHeight = buffer.short.toInt() and 0xFFFF
        val hScroll = buffer.short.toInt()
        val vScroll = buffer.short.toInt()

        val (scaledX, scaledY) = scaleCoordinates(x, y, videoWidth, videoHeight) ?: return

        val scrollDistance = 200
        val endY = scaledY + (vScroll * scrollDistance)
        val endX = scaledX + (hScroll * scrollDistance)

        GestureController.swipe(
            scaledX.toInt(), scaledY.toInt(),
            endX.toInt(), endY.toInt(),
            200
        )
    }

    @Suppress("UNUSED_PARAMETER")
    private fun handleBack(data: ByteArray) {
        GestureController.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
    }

    private fun handleText(data: ByteArray) {
        if (data.size < 5) return

        val buffer = ByteBuffer.wrap(data)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        buffer.get()
        val length = buffer.int

        if (data.size < 5 + length) return
        val textBytes = ByteArray(length)
        buffer.get(textBytes)
        val text = String(textBytes, Charsets.UTF_8)

        typeText(text)
    }

    private fun handleKeycode(data: ByteArray) {
        if (data.size < 14) return

        val buffer = ByteBuffer.wrap(data)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        buffer.get() // skip type byte
        val action = buffer.get().toInt() and 0xFF
        val keycode = buffer.int
        buffer.int // repeat
        val metaState = buffer.int

        // Only handle key down events
        if (action != ACTION_DOWN) return

        // Handle special system keycodes
        when (keycode) {
            KeyEvent.KEYCODE_BACK -> {
                GestureController.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                return
            }
            KeyEvent.KEYCODE_HOME -> {
                GestureController.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                return
            }
            KeyEvent.KEYCODE_APP_SWITCH -> {
                GestureController.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
                return
            }
            KeyEvent.KEYCODE_DEL -> {
                val service = MobilerunAccessibilityService.getInstance()
                if (service != null && MobilerunKeyboardIME.isAvailable() && MobilerunKeyboardIME.isSelected(service)) {
                    val keyboard = MobilerunKeyboardIME.getInstance()
                    if (keyboard != null) {
                        keyboard.sendKeyEventDirect(keycode)
                        return
                    }
                }
                service?.deleteText(1)
                return
            }
            KeyEvent.KEYCODE_FORWARD_DEL -> {
                // Forward delete
                val service = MobilerunAccessibilityService.getInstance() ?: return
                service.deleteText(1, forward = true)
                return
            }
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                val service = MobilerunAccessibilityService.getInstance()
                if (service != null && MobilerunKeyboardIME.isAvailable() && MobilerunKeyboardIME.isSelected(service)) {
                    val keyboard = MobilerunKeyboardIME.getInstance()
                    if (keyboard != null) {
                        keyboard.sendKeyEventDirect(keycode)
                        return
                    }
                }

                typeText("\n")
                return

            }
            KeyEvent.KEYCODE_TAB -> {
                val service = MobilerunAccessibilityService.getInstance()
                if (service != null && MobilerunKeyboardIME.isAvailable() && MobilerunKeyboardIME.isSelected(service)) {
                    val keyboard = MobilerunKeyboardIME.getInstance()
                    if (keyboard != null) {
                        keyboard.sendKeyEventDirect(keycode)
                        return
                    }
                }

                typeText("\t")
                return
            }
        }

        // For other keycodes, convert to character using KeyEvent
        val keyEvent = KeyEvent(KeyEvent.ACTION_DOWN, keycode)
        val unicodeChar = keyEvent.getUnicodeChar(metaState)

        if (unicodeChar > 0) {
            val char = unicodeChar.toChar()
            typeText(char.toString())
        }
    }

    private fun handleSetClipboard(data: ByteArray) {
        if (data.size < 14) return

        val buffer = ByteBuffer.wrap(data)
        buffer.order(ByteOrder.BIG_ENDIAN)
        buffer.get()
        buffer.long
        val paste = buffer.get().toInt() != 0

        val length = buffer.int
        if (data.size < 14 + length) return

        val textBytes = ByteArray(length)
        buffer.get(textBytes)
        val text = String(textBytes, Charsets.UTF_8)

        if (paste) {
            typeText(text)
        }
    }

    private fun expandNotificationPanel() {
        GestureController.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
    }

    private fun expandSettingsPanel() {
        GestureController.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
    }

    private fun collapsePanels() {
        GestureController.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
    }

    private fun typeText(text: String) {
        val service = MobilerunAccessibilityService.getInstance() ?: return
        service.inputText(text, false)
    }

    private fun scaleCoordinates(x: Int, y: Int, videoW: Int, videoH: Int): Pair<Float, Float>? {
        val (screenW, screenH) = currentScreenSize()
        return mapFrameToScreen(x, y, videoW, videoH, screenW, screenH)
    }

    private fun currentScreenSize(): Pair<Int, Int> {
        val service = MobilerunAccessibilityService.getInstance()
        val wm = service?.getSystemService(android.content.Context.WINDOW_SERVICE) as? android.view.WindowManager

        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val bounds = wm?.maximumWindowMetrics?.bounds
            Pair(bounds?.width() ?: Resources.getSystem().displayMetrics.widthPixels,
                 bounds?.height() ?: Resources.getSystem().displayMetrics.heightPixels)
        } else {
            val metrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            wm?.defaultDisplay?.getRealMetrics(metrics)
            Pair(metrics.widthPixels, metrics.heightPixels)
        }
    }

    private fun dispatchPath(points: List<Pair<Float, Float>>, durationMs: Long) {
        if (points.isEmpty()) return

        val service = MobilerunAccessibilityService.getInstance() ?: return

        try {
            val path = Path().apply {
                moveTo(points[0].first, points[0].second)
                points.drop(1).forEach { lineTo(it.first, it.second) }
            }

            val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()

            service.dispatchGesture(gesture, null, null)
        } catch (_: Exception) {}
    }
}
