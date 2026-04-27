package com.mobilerun.portal.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Rect
import android.util.Log
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.os.SystemClock
import android.widget.Toast
import com.mobilerun.portal.R
import com.mobilerun.portal.model.ElementNode
import com.mobilerun.portal.model.PhoneState
import com.mobilerun.portal.api.ApiHandler
import com.mobilerun.portal.core.StateRepository
import com.mobilerun.portal.config.ConfigManager
import com.mobilerun.portal.input.MobilerunKeyboardIME
import com.mobilerun.portal.ui.overlay.OverlayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import java.util.concurrent.atomic.AtomicBoolean
import android.graphics.Bitmap
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.util.concurrent.CompletableFuture
import com.mobilerun.portal.events.EventHub
import com.mobilerun.portal.events.PortalWebSocketServer
import com.mobilerun.portal.events.model.EventType
import com.mobilerun.portal.events.model.PortalEvent
import com.mobilerun.portal.keepalive.KeepAliveController
import com.mobilerun.portal.keepalive.KeepAliveRecoveryActivity
import com.mobilerun.portal.triggers.TriggerRuntime
import androidx.core.app.NotificationCompat
import org.json.JSONObject

@SuppressLint("AccessibilityPolicy")
class MobilerunAccessibilityService : AccessibilityService(), ConfigManager.ConfigChangeListener {

    companion object {
        const val TAG = "MobilerunAccessibility"
        const val ACTION_DISABLE_LOCAL_WS_SERVER =
            "com.mobilerun.portal.action.DISABLE_LOCAL_WS_SERVER"
        private var instance: MobilerunAccessibilityService? = null
        private const val MIN_ELEMENT_SIZE = 5
        private const val TOAST_DEBOUNCE_MS = 60_000L
        private const val AUTO_ACCEPT_FAILURE_TOAST_DEBOUNCE_MS = 10_000L
        private const val LOCAL_WS_NOTIFICATION_CHANNEL_ID = "local_ws_connection_channel"
        private const val LOCAL_WS_NOTIFICATION_ID = 2003

        // Periodic update constants
        private const val REFRESH_INTERVAL_MS = 250L // Update every 250ms
        private const val MIN_FRAME_TIME_MS = 16L // Minimum time between frames (roughly 60 FPS)

        fun getInstance(): MobilerunAccessibilityService? = instance

        fun calculateInputText(
            currentText: String?,
            hintText: String?,
            newText: String,
            clear: Boolean
        ): String {
            return calculateInputText(
                currentText = currentText,
                hintText = hintText,
                newText = newText,
                clear = clear,
                selectionStart = null,
                selectionEnd = null,
            )
        }

        fun calculateInputText(
            currentText: String?,
            hintText: String?,
            newText: String,
            clear: Boolean,
            selectionStart: Int?,
            selectionEnd: Int?,
        ): String {
            if (clear) return newText

            val safeCurrentText = currentText.orEmpty()

            // If the current text matches the hint text, treat it as empty.
            if (hintText != null && safeCurrentText == hintText) return newText

            val length = safeCurrentText.length
            val rawStart = selectionStart ?: length
            val rawEnd = selectionEnd ?: rawStart
            val start = rawStart.coerceIn(0, length)
            val end = rawEnd.coerceIn(0, length)
            val replaceStart = minOf(start, end)
            val replaceEnd = maxOf(start, end)

            val before = safeCurrentText.take(replaceStart)
            val after = safeCurrentText.substring(replaceEnd)
            return before + newText + after
        }

        fun calculateDeleteText(
            currentText: String?,
            hintText: String?,
            count: Int,
            forward: Boolean,
            selectionStart: Int?,
            selectionEnd: Int?,
        ): String? {
            if (count <= 0) return currentText

            val safeCurrentText = currentText.orEmpty()

            if (hintText != null && safeCurrentText == hintText) return null
            if (safeCurrentText.isEmpty()) return null

            val length = safeCurrentText.length
            val rawStart = selectionStart ?: length
            val rawEnd = selectionEnd ?: rawStart
            val start = rawStart.coerceIn(0, length)
            val end = rawEnd.coerceIn(0, length)
            val replaceStart = minOf(start, end)
            val replaceEnd = maxOf(start, end)

            if (replaceStart != replaceEnd) {
                return safeCurrentText.take(replaceStart) + safeCurrentText.substring(replaceEnd)
            }

            return if (forward) {
                val deleteEnd = minOf(length, replaceEnd + count)
                if (deleteEnd == replaceEnd) safeCurrentText
                else safeCurrentText.take(replaceEnd) + safeCurrentText.substring(deleteEnd)
            } else {
                val deleteStart = maxOf(0, replaceStart - count)
                if (deleteStart == replaceStart) safeCurrentText
                else safeCurrentText.substring(0, deleteStart) + safeCurrentText.substring(
                    replaceStart
                )
            }
        }
    }

    private lateinit var overlayManager: OverlayManager
    private val screenBounds = Rect()
    private lateinit var configManager: ConfigManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastWebSocketServerToastAtMs = 0L
    private var lastAutoAcceptFailureToastAtMs = 0L

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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            screenBounds.set(0, 0, bounds.width(), bounds.height())
        } else {
            val metrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            screenBounds.set(0, 0, metrics.widthPixels, metrics.heightPixels)
        }

        // Initialize ConfigManager
        configManager = ConfigManager.getInstance(this)
        configManager.addListener(this)

        // Initialize Event System
        EventHub.init(configManager)
        TriggerRuntime.initialize(this)

        // Initialize SocketServer with ApiHandler
        val stateRepo = StateRepository(this)
        val apiHandler = ApiHandler(
            stateRepo,
            { MobilerunKeyboardIME.getInstance() },
            { packageManager },
            {
                try {
                    packageManager.getPackageInfo(packageName, 0).versionName
                } catch (e: Exception) {
                    "unknown"
                }
            },
            this
        )

        actionDispatcher = ActionDispatcher(apiHandler)
        socketServer = SocketServer(apiHandler, configManager, actionDispatcher)

        isInitialized = true
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        overlayManager.showOverlay()
        instance = this
        TriggerRuntime.initialize(this)

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
        val keepAliveReconcileResult = KeepAliveController.reconcileBestEffort(this)
        keepAliveReconcileResult.deferredReason?.let { reason ->
            Log.w(
                TAG,
                "Deferred keep-awake reconcile during accessibility startup: $reason",
            )
        }

        Log.d(TAG, "Accessibility service connected and configured")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val eventPackage = event?.packageName?.toString() ?: ""
        val eventClassName = event?.className?.toString() ?: ""
        val previousPackage = currentPackageName

        // Detect package changes
        if (eventPackage.isNotEmpty() && eventPackage != currentPackageName && currentPackageName.isNotEmpty()) {
            resetOverlayState()
        }

        if (eventPackage.isNotEmpty()) {
            currentPackageName = eventPackage
            if (previousPackage.isNotEmpty() && previousPackage != eventPackage) {
                EventHub.emit(
                    PortalEvent(
                        type = EventType.APP_EXITED,
                        payload = JSONObject().apply {
                            put("package", previousPackage)
                            put("next_package", eventPackage)
                        }
                    )
                )
            }
            if (previousPackage != eventPackage) {
                EventHub.emit(
                    PortalEvent(
                        type = EventType.APP_ENTERED,
                        payload = JSONObject().apply {
                            put("package", eventPackage)
                            if (previousPackage.isNotEmpty()) {
                                put("previous_package", previousPackage)
                            }
                        }
                    )
                )
            }
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

        // Auto-accept MediaProjection dialog (only when reverse connection is active and setting is enabled)
        if (MediaProjectionAutoAccept.isMediaProjectionDialog(event, eventClassName) &&
            ReverseConnectionService.getInstance() != null &&
            configManager.screenShareAutoAcceptEnabled &&
            AutoAcceptGate.isMediaProjectionArmed()
        ) {
            val rootNode = rootInActiveWindow
            if (rootNode != null) {
                try {
                    val result = MediaProjectionAutoAccept.tryAutoAccept(rootNode, eventClassName)
                    if (result is MediaProjectionAutoAccept.AutoAcceptResult.Failed) {
                        showAutoAcceptFailedToastIfEnoughTimeIsPassed()
                    }
                } finally {
                    rootNode.recycle()
                }
            }
        }

        if (PackageInstallerAutoAccept.isInstallDialog(event, eventClassName) &&
            configManager.installAutoAcceptEnabled &&
            AutoAcceptGate.isInstallArmed()
        ) {
            val rootNode = rootInActiveWindow
            if (rootNode != null) {
                try {
                    PackageInstallerAutoAccept.tryAutoAccept(rootNode, eventClassName)
                } finally {
                    rootNode.recycle()
                }
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

    fun launchKeepAliveRecoveryActivity(
        reason: String,
        recoveryToken: Long,
    ): Boolean {
        return try {
            val intent =
                Intent(this, KeepAliveRecoveryActivity::class.java).apply {
                    flags =
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                                Intent.FLAG_ACTIVITY_NO_ANIMATION or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra(KeepAliveRecoveryActivity.EXTRA_REASON, reason)
                    putExtra(KeepAliveRecoveryActivity.EXTRA_RECOVERY_TOKEN, recoveryToken)
                }
            startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch keep-alive recovery activity", e)
            false
        }
    }

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

        val rootCandidates = collectRootCandidates()
        if (rootCandidates.isEmpty()) {
            // Nothing queryable right now (active window flake, or no a11y windows yet).
            // Preserve the previous snapshot so callers don't see a transient empty.
            synchronized(visibleElements) {
                return visibleElements.toMutableList()
            }
        }

        try {
            for ((rootNode, layer) in rootCandidates) {
                walkAsRoot(rootNode, layer, elements, indexCounter)
            }
        } finally {
            rootCandidates.forEach { (node, _) -> node.recycle() }
        }

        synchronized(visibleElements) {
            clearElementList()
            visibleElements.addAll(elements)
        }

        return elements
    }

    /**
     * Returns one or more root [AccessibilityNodeInfo]s to walk for the current
     * screen, paired with their window layer. Tries `rootInActiveWindow` first
     * (cheap, common case). When that's null — which can happen on apps whose
     * outermost window flakes through `rootInActiveWindow` mid-transition or
     * when an accessibility overlay claims focus — falls back to enumerating
     * `windows` and picking the topmost user-facing roots. Requires
     * `flagRetrieveInteractiveWindows` in the service config for the fallback
     * to populate `windows`.
     */
    private fun collectRootCandidates(): List<Pair<AccessibilityNodeInfo, Int>> {
        rootInActiveWindow?.let { return listOf(it to 0) }
        val ws = windows ?: return emptyList()
        val out = mutableListOf<Pair<AccessibilityNodeInfo, Int>>()
        try {
            ws.sortedByDescending { it.layer }
                .filter {
                    it.type == AccessibilityWindowInfo.TYPE_APPLICATION ||
                    it.type == AccessibilityWindowInfo.TYPE_SYSTEM
                }
                .forEach { w ->
                    val r = w.root
                    if (r != null) out.add(r to w.layer)
                }
        } finally {
            ws.forEach { it.recycle() }
        }
        return out
    }

    /**
     * Walks [rootNode] and accumulates visible elements into [elements].
     * If the root itself is filtered (e.g. zero-size wrapper before first
     * layout), its direct children are walked as fresh roots so their subtrees
     * aren't orphaned by the `parent?.addChild` no-op.
     */
    private fun walkAsRoot(
        rootNode: AccessibilityNodeInfo,
        windowLayer: Int,
        elements: MutableList<ElementNode>,
        indexCounter: IndexCounter,
    ) {
        val rootElement = findAllVisibleElements(rootNode, windowLayer, null, indexCounter)
        if (rootElement != null) {
            collectRootElements(rootElement, elements)
            return
        }
        // Root was filtered out — promote its visible descendants to roots.
        for (i in 0 until rootNode.childCount) {
            val childNode = rootNode.getChild(i) ?: continue
            try {
                val childRoot = findAllVisibleElements(childNode, windowLayer, null, indexCounter)
                if (childRoot != null) {
                    collectRootElements(childRoot, elements)
                }
            } finally {
                childNode.recycle()
            }
        }
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

                val nodeCopy = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    AccessibilityNodeInfo(node)
                } else {
                    @Suppress("DEPRECATION")
                    AccessibilityNodeInfo.obtain(node)
                }
                currentElement = ElementNode(
                    nodeCopy,
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

    fun inputText(text: String, clear: Boolean): Boolean {
        val root = rootInActiveWindow ?: return false

        // Strategy 1: Find focused input directly
        var targetNode = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)

        // Strategy 2: If no focus, try to find editable node in the tree
        if (targetNode == null) {
            // Simple DFS search for first editable node
            // Use a helper function
            targetNode = findEditableNode(root)
        }

        if (targetNode == null) return false

        try {
            // Logic to support both Replace (clear=true) and Append (clear=false).

            // Delegate logic to pure function for testability
            val currentText = targetNode.text?.toString()
            val hintText = targetNode.hintText?.toString()
            val effectiveCurrent =
                if (!hintText.isNullOrEmpty() && currentText == hintText) ""
                else currentText.orEmpty()
            val currentLength = effectiveCurrent.length
            val rawStart = targetNode.textSelectionStart
            val rawEnd = targetNode.textSelectionEnd
            val selectionStart =
                if (rawStart >= 0) rawStart.coerceIn(0, currentLength) else currentLength
            val selectionEnd =
                if (rawEnd >= 0) rawEnd.coerceIn(0, currentLength) else selectionStart
            val replaceStart = minOf(selectionStart, selectionEnd)

            val finalText = calculateInputText(
                currentText = currentText,
                hintText = hintText,
                newText = text,
                clear = clear,
                selectionStart = selectionStart,
                selectionEnd = selectionEnd,
            )

            val desiredSelection =
                if (clear) {
                    finalText.length
                } else {
                    (replaceStart + text.length).coerceIn(0, finalText.length)
                }

            // Note: ACTION_SET_TEXT always replaces existing content with the argument.
            // So for clear=true, we just set 'text'.
            // For clear=false, we set 'oldText + text'.

            val arguments = android.os.Bundle()
            arguments.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                finalText
            )
            val setTextSuccess =
                targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            if (!setTextSuccess) return false

            setSelectionOnFocusedInput(targetNode, desiredSelection)
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error setting text via accessibility: ${e.message}")
            return false
        } finally {
            // Don't recycle targetNode if it's root (unlikely for focus) but standard practice
            // findFocus returns a node that MUST be recycled.
            // rootInActiveWindow returns a node that MUST be recycled. 
            // We should handle recycling carefully.
            try {
                if (targetNode != root) targetNode.recycle()
                root.recycle()
            } catch (e: Exception) {
            }
        }
    }

    fun deleteText(count: Int, forward: Boolean = false): Boolean {
        val root = rootInActiveWindow ?: return false

        var targetNode = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (targetNode == null) {
            targetNode = findEditableNode(root)
        }

        if (targetNode == null) return false

        try {
            val currentText = targetNode.text?.toString() ?: return false
            val hintText = targetNode.hintText?.toString()
            val currentLength = currentText.length
            val rawStart = targetNode.textSelectionStart
            val rawEnd = targetNode.textSelectionEnd
            val selectionStart =
                if (rawStart >= 0) rawStart.coerceIn(0, currentLength) else currentLength
            val selectionEnd =
                if (rawEnd >= 0) rawEnd.coerceIn(0, currentLength) else selectionStart
            val replaceStart = minOf(selectionStart, selectionEnd)
            val replaceEnd = maxOf(selectionStart, selectionEnd)

            val newText = calculateDeleteText(
                currentText = currentText,
                hintText = hintText,
                count = count,
                forward = forward,
                selectionStart = selectionStart,
                selectionEnd = selectionEnd,
            ) ?: return false

            val desiredSelection =
                if (replaceStart != replaceEnd) {
                    replaceStart
                } else if (forward) {
                    replaceStart
                } else {
                    maxOf(0, replaceStart - count)
                }.coerceIn(0, newText.length)

            // Avoid unnecessary churn if the delete request is effectively a noop.
            if (newText == currentText) {
                setSelectionOnFocusedInput(targetNode, desiredSelection)
                return true
            }

            val arguments = android.os.Bundle()
            arguments.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                newText
            )
            val setTextSuccess =
                targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            if (!setTextSuccess) return false

            setSelectionOnFocusedInput(targetNode, desiredSelection)
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting text via accessibility: ${e.message}")
            return false
        } finally {
            try {
                if (targetNode != root) targetNode.recycle()
                root.recycle()
            } catch (e: Exception) {
            }
        }
    }

    private fun findEditableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findEditableNode(child)
            if (found != null) {
                if (found != child) child.recycle() // Recycle intermediate if not the one
                return found
            }
            child.recycle()
        }
        return null
    }

    private fun setSelectionOnFocusedInput(
        targetNode: AccessibilityNodeInfo,
        selection: Int
    ): Boolean {
        val args = android.os.Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, selection)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, selection)
        }

        if (targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)) {
            return true
        }

        val focusedNode = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        return try {
            focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)
        } finally {
            try {
                if (focusedNode != targetNode) focusedNode.recycle()
            } catch (_: Exception) {
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
                websocketServer = PortalWebSocketServer(
                    port,
                    actionDispatcher,
                    configManager,
                ) {
                    showWebSocketServerStartedToastIfEnoughTimeIsPassed(port)
                    showLocalWebSocketConnectionNotificationIfEligible()
                }
                websocketServer?.start()
                Log.i(TAG, "WebSocket server started on port $port")
            }
        } catch (e: Exception) {
            hideLocalWebSocketConnectionNotification()
            Log.e(TAG, "Failed to start WebSocket server", e)
        }
    }

    private fun showWebSocketServerStartedToastIfEnoughTimeIsPassed(port: Int) {
        val now = SystemClock.elapsedRealtime()
        if (lastWebSocketServerToastAtMs == 0L ||
            now - lastWebSocketServerToastAtMs >= TOAST_DEBOUNCE_MS
        ) {
            lastWebSocketServerToastAtMs = now
            mainHandler.post {
                Toast.makeText(
                    this@MobilerunAccessibilityService,
                    getString(R.string.websocket_server_started, port),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    private fun showAutoAcceptFailedToastIfEnoughTimeIsPassed() {
        val now = SystemClock.elapsedRealtime()
        if (lastAutoAcceptFailureToastAtMs == 0L ||
            now - lastAutoAcceptFailureToastAtMs >= AUTO_ACCEPT_FAILURE_TOAST_DEBOUNCE_MS
        ) {
            lastAutoAcceptFailureToastAtMs = now
            mainHandler.post {
                Toast.makeText(
                    this@MobilerunAccessibilityService,
                    getString(R.string.media_projection_auto_accept_failed),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun stopWebSocketServer() {
        try {
            websocketServer?.stopSafely()
            websocketServer = null
            Log.i(TAG, "WebSocket server stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping WebSocket server", e)
        } finally {
            hideLocalWebSocketConnectionNotification()
        }
    }

    fun showLocalWebSocketConnectionNotificationIfEligible() {
        if (!shouldShowLocalWebSocketConnectionNotification()) {
            hideLocalWebSocketConnectionNotification()
            return
        }

        try {
            createLocalWebSocketNotificationChannel()
            val manager = getSystemService(NotificationManager::class.java) ?: return
            manager.notify(LOCAL_WS_NOTIFICATION_ID, createLocalWebSocketNotification())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show local WS connection notification", e)
        }
    }

    fun hideLocalWebSocketConnectionNotification() {
        try {
            val manager = getSystemService(NotificationManager::class.java) ?: return
            manager.cancel(LOCAL_WS_NOTIFICATION_ID)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to hide local WS connection notification", e)
        }
    }

    private fun shouldShowLocalWebSocketConnectionNotification(): Boolean {
        if (!configManager.websocketEnabled) return false
        if (websocketServer == null) return false
        if (ReverseConnectionService.getInstance() != null) return false
        return true
    }

    private fun createLocalWebSocketNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            LOCAL_WS_NOTIFICATION_CHANNEL_ID,
            "Local Connection",
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    private fun createLocalWebSocketNotification(): Notification {
        val intent = Intent(this, com.mobilerun.portal.ui.MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            2,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val disableIntent = Intent(this, LocalWsNotificationActionReceiver::class.java).apply {
            action = ACTION_DISABLE_LOCAL_WS_SERVER
        }
        val disablePendingIntent = PendingIntent.getBroadcast(
            this,
            3,
            disableIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, LOCAL_WS_NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.local_connection_service_title))
            .setContentText(getString(R.string.local_connection_service_text))
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(pendingIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.disable_ws_server_action),
                disablePendingIntent,
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
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
        if (port !in 1..65535) {
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
    //
    // Dispatch by API level:
    //   API 30+: AccessibilityService.takeScreenshot() (fast path)
    //   API 26-29: MediaProjectionScreenshotter — which in turn delegates to
    //     WebRtcManager.captureStreamFrame() if a stream is already active
    //     (a second MediaProjection cannot run concurrently with the streamer's).
    fun takeScreenshotBase64(hideOverlay: Boolean = true): CompletableFuture<String> {
        val future = CompletableFuture<String>()

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

    private fun performScreenshotCapture(
        future: CompletableFuture<String>,
        wasOverlayDrawingEnabled: Boolean,
        hideOverlay: Boolean,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            performAccessibilityScreenshot(future, wasOverlayDrawingEnabled, hideOverlay)
        } else {
            performMediaProjectionScreenshot(future, wasOverlayDrawingEnabled, hideOverlay)
        }
    }

    private fun performMediaProjectionScreenshot(
        future: CompletableFuture<String>,
        wasOverlayDrawingEnabled: Boolean,
        hideOverlay: Boolean,
    ) {
        try {
            val fallback = MediaProjectionScreenshotter.getInstance(this).capture()
            fallback.whenComplete { result, error ->
                try {
                    val value = when {
                        error != null -> "error: ${error.message}"
                        result != null -> result
                        else -> "error: empty_result"
                    }
                    future.complete(value)
                } finally {
                    if (hideOverlay) {
                        overlayManager.setDrawingEnabled(wasOverlayDrawingEnabled)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting MediaProjection screenshot", e)
            future.complete("error: Failed to take screenshot: ${e.message}")
            if (hideOverlay) {
                overlayManager.setDrawingEnabled(wasOverlayDrawingEnabled)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun performAccessibilityScreenshot(
        future: CompletableFuture<String>,
        wasOverlayDrawingEnabled: Boolean,
        hideOverlay: Boolean,
    ) {
        try {
            AccessibilityScreenshotApi30.takeScreenshot(
                service = this,
                tag = TAG,
                onSuccess = { base64 ->
                    try {
                        future.complete(base64)
                    } finally {
                        if (hideOverlay) {
                            overlayManager.setDrawingEnabled(wasOverlayDrawingEnabled)
                        }
                    }
                },
                onFailure = { message ->
                    try {
                        future.complete("error: $message")
                    } finally {
                        if (hideOverlay) {
                            overlayManager.setDrawingEnabled(wasOverlayDrawingEnabled)
                        }
                    }
                },
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
        hideLocalWebSocketConnectionNotification()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPeriodicUpdates()
        stopSocketServer()
        stopWebSocketServer()
        hideLocalWebSocketConnectionNotification()

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
