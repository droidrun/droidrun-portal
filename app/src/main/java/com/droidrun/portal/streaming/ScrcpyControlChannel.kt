package com.droidrun.portal.streaming

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.res.Resources
import android.graphics.Path
import android.view.KeyEvent
import com.droidrun.portal.service.DroidrunAccessibilityService
import com.droidrun.portal.service.GestureController
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
    }

    private val touchPath = mutableListOf<Pair<Float, Float>>()
    private var touchStartTime = 0L
    private var lastVideoWidth = 0
    private var lastVideoHeight = 0

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
        buffer.long

        buffer.order(ByteOrder.LITTLE_ENDIAN)
        val x = buffer.int
        val y = buffer.int
        val videoWidth = buffer.short.toInt() and 0xFFFF
        val videoHeight = buffer.short.toInt() and 0xFFFF
        buffer.short

        lastVideoWidth = videoWidth
        lastVideoHeight = videoHeight

        val (scaledX, scaledY) = scaleCoordinates(x, y, videoWidth, videoHeight)

        when (action) {
            ACTION_DOWN -> {
                touchPath.clear()
                touchPath.add(Pair(scaledX, scaledY))
                touchStartTime = System.currentTimeMillis()
            }
            ACTION_MOVE -> {
                touchPath.add(Pair(scaledX, scaledY))
            }
            ACTION_UP -> {
                touchPath.add(Pair(scaledX, scaledY))
                val duration = (System.currentTimeMillis() - touchStartTime)
                    .coerceIn(MIN_GESTURE_DURATION_MS, MAX_GESTURE_DURATION_MS)
                dispatchPath(touchPath.toList(), duration)
                touchPath.clear()
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

        val (scaledX, scaledY) = scaleCoordinates(x, y, videoWidth, videoHeight)

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
                // Backspace - delete last character
                val service = DroidrunAccessibilityService.getInstance() ?: return
                service.deleteText(1)
                return
            }
            KeyEvent.KEYCODE_FORWARD_DEL -> {
                // Forward delete
                val service = DroidrunAccessibilityService.getInstance() ?: return
                service.deleteText(1, forward = true)
                return
            }
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                typeText("\n")
                return
            }
            KeyEvent.KEYCODE_TAB -> {
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
        val service = DroidrunAccessibilityService.getInstance() ?: return
        service.inputText(text, false)
    }

    private fun scaleCoordinates(x: Int, y: Int, videoW: Int, videoH: Int): Pair<Float, Float> {
        if (videoW <= 0 || videoH <= 0) return Pair(x.toFloat(), y.toFloat())

        val service = DroidrunAccessibilityService.getInstance()
        val wm = service?.getSystemService(android.content.Context.WINDOW_SERVICE) as? android.view.WindowManager

        val (screenW, screenH) = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val bounds = wm?.maximumWindowMetrics?.bounds
            Pair(bounds?.width() ?: Resources.getSystem().displayMetrics.widthPixels,
                 bounds?.height() ?: Resources.getSystem().displayMetrics.heightPixels)
        } else {
            val metrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            wm?.defaultDisplay?.getRealMetrics(metrics)
            Pair(metrics.widthPixels, metrics.heightPixels)
        }

        val scaledX = (x.toFloat() / videoW) * screenW
        val scaledY = (y.toFloat() / videoH) * screenH

        return Pair(scaledX, scaledY)
    }

    private fun dispatchPath(points: List<Pair<Float, Float>>, durationMs: Long) {
        if (points.isEmpty()) return

        val service = DroidrunAccessibilityService.getInstance() ?: return

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
