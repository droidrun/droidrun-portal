package com.droidrun.portal.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.graphics.Rect
import android.util.Log
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.droidrun.portal.model.ElementNode
import com.droidrun.portal.model.PhoneState
import com.droidrun.portal.api.ApiHandler
import com.droidrun.portal.core.StateRepository
import com.droidrun.portal.config.ConfigManager
import com.droidrun.portal.input.DroidrunKeyboardIME
import com.droidrun.portal.ui.overlay.OverlayManager
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean
import android.graphics.Bitmap
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.util.concurrent.CompletableFuture

// Event System Imports
import com.droidrun.portal.events.EventHub
import com.droidrun.portal.events.PortalWebSocketServer

@SuppressLint("AccessibilityPolicy")
class DroidrunAccessibilityService : AccessibilityService(), ConfigManager.ConfigChangeListener {

    companion object {
        private const val TAG = "DroidrunA11yService"
        private var instance: DroidrunAccessibilityService? = null
        private const val MIN_ELEMENT_SIZE = 5

        // Periodic update constants
        private const val REFRESH_INTERVAL_MS = 250L // Update every 250ms
        private const val MIN_FRAME_TIME_MS = 16L // Minimum time between frames (roughly 60 FPS)

        fun getInstance(): DroidrunAccessibilityService? = instance
    }

    private lateinit var overlayManager: OverlayManager
    private val screenBounds = Rect()
    private lateinit var configManager: ConfigManager
    private val mainHandler = Handler(Looper.getMainLooper())

    // Servers
    // TODO Make nullable
    private lateinit var actionDispatcher: ActionDispatcher
    private var socketServer: SocketServer? = null
    private var websocketServer: PortalWebSocketServer? = null

    // Periodic update state
    private var isInitialized = false
    private val isProcessing = AtomicBoolean(false)
    private var lastUpdateTime = 0L
    private var currentPackageName: String = ""
    private var currentActivityName: String = ""
    private val visibleElements = mutableListOf<ElementNode>()

    override fun onCreate() {
        super.onCreate()
        overlayManager = OverlayManager(this)
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        // TODO increase SDK version to 30
        val windowMetrics = windowManager.currentWindowMetrics
        val bounds = windowMetrics.bounds
        screenBounds.set(0, 0, bounds.width(), bounds.height())

        // Initialize ConfigManager
        configManager = ConfigManager.getInstance(this)
        configManager.addListener(this)

        // Initialize Event System
        EventHub.init(configManager)

        // Initialize SocketServer with ApiHandler
        val stateRepo = StateRepository(this)
        val apiHandler = ApiHandler(
            stateRepo = stateRepo,
            getKeyboardIME = { DroidrunKeyboardIME.getInstance() },
            getPackageManager = { packageManager },
            appVersionProvider = {
                try {
                    packageManager.getPackageInfo(packageName, 0).versionName
                } catch (e: Exception) {
                    "unknown"
                }
            }
        )

        actionDispatcher = ActionDispatcher(apiHandler)
        socketServer = SocketServer(apiHandler, configManager, actionDispatcher)

        isInitialized = true
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        overlayManager.showOverlay()
        instance = this

        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK

            packageNames = null

            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC

            // Set flags for better access
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE

            // Enable screenshot capability (API 34+)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_2_FINGER_PASSTHROUGH
            }
        }

        applyConfiguration()

        startPeriodicUpdates()

        startSocketServerIfEnabled()
        startWebSocketServerIfEnabled()

        Log.d(TAG, "Accessibility service connected and configured")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val eventPackage = event?.packageName?.toString() ?: ""
        val eventClassName = event?.className?.toString() ?: ""

        // Detect package changes
        if (eventPackage.isNotEmpty() && eventPackage != currentPackageName && currentPackageName.isNotEmpty()) {
            resetOverlayState()
        }

        if (eventPackage.isNotEmpty()) {
            currentPackageName = eventPackage
        }

        // Capture activity name from TYPE_WINDOW_STATE_CHANGED events
        // These events typically indicate navigation to a new activity/screen
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (eventClassName.isNotEmpty() && !eventClassName.startsWith("android.")) {
                // Filter out Android system dialogs and only keep app activities
                currentActivityName = eventClassName
                Log.d(TAG, "Activity changed: $currentActivityName")
            }
        }

        // Trigger update on relevant events
        when (event?.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                // Use a faster handling instead of clearing elements
            }
        }
    }

    // Periodic update runnable
    private val updateRunnable = object : Runnable {
        override fun run() {
            if (isInitialized && configManager.overlayVisible) {
                val currentTime = System.currentTimeMillis()
                val timeSinceLastUpdate = currentTime - lastUpdateTime

                if (timeSinceLastUpdate >= MIN_FRAME_TIME_MS) {
                    refreshVisibleElements()
                    lastUpdateTime = currentTime
                }
            }
            mainHandler.postDelayed(this, REFRESH_INTERVAL_MS)
        }
    }

    private fun startPeriodicUpdates() {
        lastUpdateTime = System.currentTimeMillis()
        mainHandler.postDelayed(updateRunnable, REFRESH_INTERVAL_MS)
        Log.d(TAG, "Started periodic updates")
    }

    private fun stopPeriodicUpdates() {
        mainHandler.removeCallbacks(updateRunnable)
        Log.d(TAG, "Stopped periodic updates")
    }

    private fun refreshVisibleElements() {
        if (!isProcessing.compareAndSet(false, true)) {
            return // Already processing
        }

        try {
            if (currentPackageName.isEmpty()) {
                overlayManager.clearElements()
                overlayManager.refreshOverlay()
                return
            }

            // Clear previous elements
            clearElementList()

            // Get fresh elements
            val elements = getVisibleElementsInternal()

            // Update overlay if visible
            if (configManager.overlayVisible && elements.isNotEmpty()) {
                overlayManager.clearElements()

                elements.forEach { rootElement ->
                    addElementAndChildrenToOverlay(rootElement, 0)
                }

                overlayManager.refreshOverlay()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error refreshing visible elements: ${e.message}", e)
        } finally {
            isProcessing.set(false)
        }
    }

    private fun applyAutoOffset() {
        val autoOffset = overlayManager.calculateAutoOffset()
        configManager.overlayOffset = autoOffset
        overlayManager.setPositionOffsetY(autoOffset)
    }

    private fun resetOverlayState() {
        try {
            overlayManager.clearElements()
            overlayManager.refreshOverlay()
            clearElementList()
            Log.d(TAG, "Reset overlay state for package change")
        } catch (e: Exception) {
            Log.e(TAG, "Error resetting overlay state: ${e.message}", e)
        }
    }

    private fun clearElementList() {
        for (element in visibleElements) {
            try {
                element.nodeInfo.recycle()
            } catch (e: Exception) {
                Log.e(TAG, "Error recycling node: ${e.message}")
            }
        }
        visibleElements.clear()
    }

    private fun applyConfiguration() {
        mainHandler.post {
            try {
                val config = configManager.getCurrentConfiguration()
                if (config.overlayVisible) {
                    overlayManager.showOverlay()
                } else {
                    overlayManager.hideOverlay()
                }

                // Apply offset: auto or manual
                val offsetToApply = if (config.autoOffsetEnabled) {
                    // Only calculate auto offset if it hasn't been calculated before
                    if (!config.autoOffsetCalculated) {
                        val autoOffset = overlayManager.calculateAutoOffset()
                        // Save the calculated auto offset back to ConfigManager
                        // so MainActivity can read the correct value
                        configManager.overlayOffset = autoOffset
                        // Mark that auto offset has been calculated
                        configManager.autoOffsetCalculated = true
                        Log.d(TAG, "Auto offset calculated for the first time: $autoOffset")
                        autoOffset
                    } else {
                        // Use the previously calculated/saved offset
                        val savedOffset = config.overlayOffset
                        Log.d(TAG, "Using previously calculated auto offset: $savedOffset")
                        savedOffset
                    }
                } else {
                    config.overlayOffset
                }

                overlayManager.setPositionOffsetY(offsetToApply)
            } catch (e: Exception) {
                Log.e(TAG, "Error applying configuration: ${e.message}", e)
            }
        }
    }

    // Public methods for MainActivity to call directly
    fun setOverlayVisible(visible: Boolean): Boolean {
        return try {
            configManager.overlayVisible = visible

            mainHandler.post {
                if (visible) {
                    overlayManager.showOverlay()
                    // Trigger immediate refresh when showing overlay
                    refreshVisibleElements()
                } else {
                    overlayManager.hideOverlay()
                }
            }

            Log.d(TAG, "Overlay visibility set to: $visible")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error setting overlay visibility: ${e.message}", e)
            false
        }
    }

    fun isOverlayVisible(): Boolean = configManager.overlayVisible

    fun setOverlayOffset(offset: Int): Boolean {
        return try {
            configManager.overlayOffset = offset

            mainHandler.post {
                overlayManager.setPositionOffsetY(offset)
            }

            Log.d(TAG, "Overlay offset set to: $offset")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error setting overlay offset: ${e.message}", e)
            false
        }
    }

    fun getOverlayOffset(): Int = configManager.overlayOffset

    fun getCurrentAppliedOffset(): Int = overlayManager.getPositionOffsetY()

    fun getScreenBounds(): Rect = screenBounds

    fun getActionDispatcher(): ActionDispatcher = actionDispatcher

    fun setAutoOffsetEnabled(enabled: Boolean): Boolean {
        return try {
            if (!enabled) {
                // When disabling auto-offset, save the current applied offset
                // as the manual offset so it persists across restarts
                configManager.overlayOffset = overlayManager.getPositionOffsetY()
                // Reset the calculated flag so it will recalculate if re-enabled
                configManager.autoOffsetCalculated = false
            } else {
                // When enabling, reset the calculated flag to trigger recalculation
                configManager.autoOffsetCalculated = false
            }

            configManager.autoOffsetEnabled = enabled

            // Only recalculate when enabling auto-offset
            if (enabled) {
                mainHandler.post {
                    val autoOffset = overlayManager.calculateAutoOffset()
                    // Save the calculated auto offset back to ConfigManager
                    // so MainActivity can read the correct value
                    configManager.overlayOffset = autoOffset
                    // Mark that auto offset has been calculated
                    configManager.autoOffsetCalculated = true
                    overlayManager.setPositionOffsetY(autoOffset)
                    Log.d(TAG, "Auto offset recalculated: $autoOffset")
                }
            }

            true
        } catch (e: Exception) {
            Log.e(TAG, "Error setting auto offset: ${e.message}", e)
            false
        }
    }

    fun isAutoOffsetEnabled(): Boolean = configManager.autoOffsetEnabled

    fun getVisibleElements(): MutableList<ElementNode> {
        return getVisibleElementsInternal()
    }

    private fun getVisibleElementsInternal(): MutableList<ElementNode> {
        val elements = mutableListOf<ElementNode>()
        val indexCounter = IndexCounter(1)

        val rootNode = rootInActiveWindow ?: return elements
        try {
            val rootElement = findAllVisibleElements(rootNode, 0, null, indexCounter)
            rootElement?.let {
                collectRootElements(it, elements)
            }
        } finally {
            rootNode.recycle()
        }

        synchronized(visibleElements) {
            clearElementList()
            visibleElements.addAll(elements)
        }

        return elements
    }

    private fun collectRootElements(element: ElementNode, rootElements: MutableList<ElementNode>) {
        rootElements.add(element)
    }

    private fun findAllVisibleElements(
        node: AccessibilityNodeInfo,
        windowLayer: Int,
        parent: ElementNode?,
        indexCounter: IndexCounter
    ): ElementNode? {
        try {

            val rect = Rect()
            node.getBoundsInScreen(rect)

            val isInScreen = Rect.intersects(rect, screenBounds)
            val hasSize = rect.width() > MIN_ELEMENT_SIZE && rect.height() > MIN_ELEMENT_SIZE

            var currentElement: ElementNode? = null

            if (isInScreen && hasSize) {
                val text = node.text?.toString() ?: ""
                val contentDesc = node.contentDescription?.toString() ?: ""
                val className = node.className?.toString() ?: ""
                val viewId = node.viewIdResourceName ?: ""

                val displayText = when {
                    text.isNotEmpty() -> text
                    contentDesc.isNotEmpty() -> contentDesc
                    viewId.isNotEmpty() -> viewId.substringAfterLast('/')
                    else -> className.substringAfterLast('.')
                }

                val elementType = if (node.isClickable) {
                    "Clickable"
                } else if (node.isCheckable) {
                    "Checkable"
                } else if (node.isEditable) {
                    "Input"
                } else if (text.isNotEmpty()) {
                    "Text"
                } else if (node.isScrollable) {
                    "Container"
                } else {
                    "View"
                }

                val id = ElementNode.createId(rect, className.substringAfterLast('.'), displayText)

                currentElement = ElementNode(
                    AccessibilityNodeInfo(node),
                    Rect(rect),
                    displayText,
                    className.substringAfterLast('.'),
                    windowLayer,
                    System.currentTimeMillis(),
                    id
                )

                // Assign unique index
                currentElement.overlayIndex = indexCounter.getNext()

                // Set parent-child relationship
                parent?.addChild(currentElement)
            }

            // Recursively process children
            for (i in 0 until node.childCount) {
                val childNode = node.getChild(i) ?: continue
                try {
                    findAllVisibleElements(childNode, windowLayer, currentElement, indexCounter)
                } finally {
                    childNode.recycle()
                }
            }

            return currentElement

        } catch (e: Exception) {
            Log.e(TAG, "Error in findAllVisibleElements: ${e.message}", e)
            return null
        }
    }

    fun getPhoneState(): PhoneState {
        val focusedNode = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findFocus(
            AccessibilityNodeInfo.FOCUS_ACCESSIBILITY
        )
        val isEditable = focusedNode?.isEditable ?: false
        val keyboardVisible = detectKeyboardVisibility()
        val currentPackage = rootInActiveWindow?.packageName?.toString()
        val appName = getAppName(currentPackage)

        return PhoneState(
            focusedNode,
            keyboardVisible,
            currentPackage,
            appName,
            isEditable,
            currentActivityName,
        )
    }

    private fun detectKeyboardVisibility(): Boolean {
        try {
            val windows = windows
            if (windows != null) {
                val hasInputMethodWindow =
                    windows.any { window -> window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
                windows.forEach { it.recycle() }
                return hasInputMethodWindow
            } else {
                return false
            }
        } catch (e: Exception) {
            return false
        }
    }

    private fun getAppName(packageName: String?): String? {
        return try {
            if (packageName == null) return null

            val packageManager = packageManager
            val applicationInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(applicationInfo).toString()
        } catch (e: Exception) {
            Log.e(TAG, "Error getting app name for package $packageName: ${e.message}")
            null
        }
    }

    // Helper class to maintain global index counter
    private class IndexCounter(private var current: Int = 1) {
        fun getNext(): Int = current++
    }

    fun getDeviceContext(): org.json.JSONObject {
        return org.json.JSONObject().apply {
            // Screen dimensions
            put("screen_bounds", org.json.JSONObject().apply {
                put("width", screenBounds.width())
                put("height", screenBounds.height())
            })

            // Filtering parameters
            put("filtering_params", org.json.JSONObject().apply {
                put("min_element_size", MIN_ELEMENT_SIZE)
                put("overlay_offset", getOverlayOffset())
            })

            // Display metrics
            val metrics = resources.displayMetrics
            put("display_metrics", org.json.JSONObject().apply {
                put("density", metrics.density)
                put("densityDpi", metrics.densityDpi)
                put("scaledDensity", metrics.scaledDensity)
                put("widthPixels", metrics.widthPixels)
                put("heightPixels", metrics.heightPixels)
            })
        }
    }

    // Socket server management methods
    private fun startSocketServerIfEnabled() {
        if (configManager.socketServerEnabled) {
            startSocketServer()
        }
    }

    private fun startSocketServer() {
        socketServer?.let { server ->
            if (!server.isRunning()) {
                val port = configManager.socketServerPort
                val success = server.start(port)
                if (success) {
                    Log.i(TAG, "Socket server started on port $port")
                } else {
                    Log.e(TAG, "Failed to start socket server on port $port")
                }
            }
        }
    }

    private fun stopSocketServer() {
        socketServer?.let { server ->
            if (server.isRunning()) {
                server.stop()
                Log.i(TAG, "Socket server stopped")
            }
        }
    }

    fun getSocketServerStatus(): String {
        return socketServer?.let { server ->
            if (server.isRunning()) {
                "Running on port ${server.getPort()}"
            } else {
                "Stopped"
            }
        } ?: "Not initialized"
    }

    fun getAdbForwardCommand(): String {
        val port = configManager.socketServerPort
        return "adb forward tcp:$port tcp:$port"
    }

    // WebSocket Management
    private fun startWebSocketServerIfEnabled() {
        if (configManager.websocketEnabled) {
            startWebSocketServer()
        }
    }

    private fun startWebSocketServer() {
        try {
            if (websocketServer == null) {
                val port = configManager.websocketPort
                websocketServer = PortalWebSocketServer(port, actionDispatcher, configManager)
                websocketServer?.start()
                Log.i(TAG, "WebSocket server started on port $port")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start WebSocket server", e)
        }
    }

    private fun stopWebSocketServer() {
        try {
            websocketServer?.stopSafely()
            websocketServer = null
            Log.i(TAG, "WebSocket server stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping WebSocket server", e)
        }
    }

    // ConfigManager.ConfigChangeListener implementation
    override fun onOverlayVisibilityChanged(visible: Boolean) {
        // Already handled in setOverlayVisible method
    }

    override fun onOverlayOffsetChanged(offset: Int) {
        // Already handled in setOverlayOffset method
    }

    override fun onSocketServerEnabledChanged(enabled: Boolean) {
        if (enabled) {
            startSocketServer()
        } else {
            stopSocketServer()
        }
    }

    override fun onSocketServerPortChanged(port: Int) {
        // Restart server with new port if enabled
        socketServer?.let { server ->
            val wasRunning = server.isRunning()
            if (wasRunning) {
                server.stop()
            }

            // Start server on new port if it was running or if socket server is enabled
            if (wasRunning || configManager.socketServerEnabled) {
                val success = server.start(port)
                if (success) {
                    Log.i(TAG, "Socket server started on new port $port")
                } else {
                    Log.e(TAG, "Failed to start socket server on new port $port")
                }
            }
        }
    }

    override fun onWebSocketEnabledChanged(enabled: Boolean) {
        if (enabled) {
            startWebSocketServer()
        } else {
            stopWebSocketServer()
        }
    }

    override fun onWebSocketPortChanged(port: Int) {
        stopWebSocketServer()
        if (configManager.websocketEnabled) {
            startWebSocketServer()
        }
    }

    fun updateSocketServerPort(port: Int): Boolean {
        if (port < 1 || port > 65535) {
            Log.e(TAG, "Invalid port: $port")
            return false
        }

        val server = socketServer ?: return false

        // If port hasn't changed, just return true
        if (server.getPort() == port && server.isRunning()) {
            return true
        }

        val oldPort = server.getPort()
        val wasRunning = server.isRunning()

        // Stop current server
        if (wasRunning) server.stop()


        // Try to start on new port
        val success = server.start(port)

        if (success) {
            // Update config without triggering listener notification loop
            // We use the property setter which persists but doesn't notify listeners
            configManager.socketServerPort = port
            Log.i(TAG, "Successfully updated socket server port to $port")
            return true
        } else {
            Log.e(TAG, "Failed to bind to new port $port, reverting to $oldPort")
            // Revert to old port if it was running
            if (wasRunning) {
                server.start(oldPort)
            }
            return false
        }
    }

    // Screenshot functionality
    fun takeScreenshotBase64(hideOverlay: Boolean = true): CompletableFuture<String> {
        val future = CompletableFuture<String>()

        // Check if screenshot API is available (API 34+)
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            future.complete("error: Screenshot API requires Android 14 (API 34) or higher")
            return future
        }

        // Temporarily hide overlay if requested
        val wasOverlayDrawingEnabled = if (hideOverlay) {
            val enabled = overlayManager.isDrawingEnabled()
            overlayManager.setDrawingEnabled(false)
            enabled
        } else {
            true
        }

        try {
            if (hideOverlay) {
                // Small delay to ensure overlay is hidden before screenshot
                mainHandler.postDelayed({
                    performScreenshotCapture(future, wasOverlayDrawingEnabled, hideOverlay)
                }, 100)
            } else {
                performScreenshotCapture(future, wasOverlayDrawingEnabled, hideOverlay)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error taking screenshot", e)
            future.complete("error: Failed to take screenshot: ${e.message}")

            // Restore overlay drawing state in case of exception
            if (hideOverlay) {
                overlayManager.setDrawingEnabled(wasOverlayDrawingEnabled)
            }
        }

        return future
    }

    @androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun performScreenshotCapture(
        future: CompletableFuture<String>,
        wasOverlayDrawingEnabled: Boolean,
        hideOverlay: Boolean,
    ) {
        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainHandler.looper.thread.contextClassLoader?.let {
                    java.util.concurrent.Executors.newSingleThreadExecutor()
                } ?: java.util.concurrent.Executors.newSingleThreadExecutor(),
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshotResult: ScreenshotResult) {
                        try {
                            val bitmap = Bitmap.wrapHardwareBuffer(
                                screenshotResult.hardwareBuffer,
                                screenshotResult.colorSpace
                            )

                            if (bitmap == null) {
                                Log.e(TAG, "Failed to create bitmap from hardware buffer")
                                screenshotResult.hardwareBuffer.close()
                                future.complete("error: Failed to create bitmap from screenshot data")
                                return
                            }

                            val byteArrayOutputStream = ByteArrayOutputStream()
                            val compressionSuccess = bitmap.compress(
                                Bitmap.CompressFormat.PNG,
                                100,
                                byteArrayOutputStream,
                            )

                            if (!compressionSuccess) {
                                Log.e(TAG, "Failed to compress bitmap to PNG")
                                bitmap.recycle()
                                screenshotResult.hardwareBuffer.close()
                                byteArrayOutputStream.close()
                                future.complete("error: Failed to compress screenshot to PNG format")
                                return
                            }

                            val byteArray = byteArrayOutputStream.toByteArray()
                            val base64String = Base64.encodeToString(byteArray, Base64.NO_WRAP)

                            bitmap.recycle()
                            screenshotResult.hardwareBuffer.close()
                            byteArrayOutputStream.close()

                            future.complete(base64String)
                            Log.d(
                                TAG,
                                "Screenshot captured successfully, size: ${byteArray.size} bytes",
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "Error processing screenshot", e)
                            try {
                                screenshotResult.hardwareBuffer.close()
                            } catch (closeException: Exception) {
                                Log.e(TAG, "Error closing hardware buffer", closeException)
                            }
                            future.complete("error: Failed to process screenshot: ${e.message}")
                        } finally {
                            // Restore overlay drawing state
                            if (hideOverlay) {
                                overlayManager.setDrawingEnabled(wasOverlayDrawingEnabled)
                            }
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        val errorMessage = when (errorCode) {
                            ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR -> "Internal error occurred"
                            ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "Screenshot interval too short"
                            ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> "Invalid display"
                            ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "No accessibility access"
                            ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> "Secure window cannot be captured"
                            else -> "Unknown error (code: $errorCode)"
                        }
                        Log.e(TAG, "Screenshot failed: $errorMessage")
                        future.complete("error: Screenshot failed: $errorMessage")

                        // Restore overlay drawing state
                        if (hideOverlay) {
                            overlayManager.setDrawingEnabled(wasOverlayDrawingEnabled)
                        }
                    }
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error taking screenshot", e)
            future.complete("error: Failed to take screenshot: ${e.message}")

            // Restore overlay drawing state in case of exception
            if (hideOverlay) {
                overlayManager.setDrawingEnabled(wasOverlayDrawingEnabled)
            }
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility service interrupted")
        stopPeriodicUpdates()
        stopSocketServer()
        stopWebSocketServer()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPeriodicUpdates()
        stopSocketServer()
        stopWebSocketServer()

        clearElementList()
        configManager.removeListener(this)
        instance = null
        Log.d(TAG, "Accessibility service destroyed")
    }

    private fun addElementAndChildrenToOverlay(element: ElementNode, depth: Int) {
        overlayManager.addElement(
            text = element.text,
            rect = element.rect,
            type = element.className,
            index = element.overlayIndex
        )

        for (child in element.children) {
            addElementAndChildrenToOverlay(child, depth + 1)
        }
    }
}
