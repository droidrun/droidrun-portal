package com.droidrun.portal.api

import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
import android.Manifest
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.content.Intent
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.net.Uri
import android.provider.Settings
import com.droidrun.portal.input.DroidrunKeyboardIME
import com.droidrun.portal.core.JsonBuilders
import com.droidrun.portal.core.StateRepository
import com.droidrun.portal.service.GestureController
import com.droidrun.portal.service.DroidrunAccessibilityService
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import android.content.pm.PackageInstaller
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.io.FilterInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import androidx.core.net.toUri
import com.droidrun.portal.service.ScreenCaptureService
import com.droidrun.portal.streaming.WebRtcManager
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import com.droidrun.portal.service.AutoAcceptGate
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import com.droidrun.portal.state.AppVisibilityTracker
import com.droidrun.portal.ui.PermissionDialogActivity
import java.util.Locale

class ApiHandler(
    private val stateRepo: StateRepository,
    private val getKeyboardIME: () -> DroidrunKeyboardIME?,
    private val getPackageManager: () -> PackageManager,
    private val appVersionProvider: () -> String,
    private val context: Context,
) {
    companion object {
        private const val SCREENSHOT_TIMEOUT_SECONDS = 5L
        private const val TAG = "ApiHandler"
        private const val MAX_APK_BYTES = 2L * 1024 * 1024 * 1024 // 2 GB
        private const val INSTALL_FREE_SPACE_MARGIN_BYTES = 200L * 1024 * 1024 // 200 MiB
        private const val INSTALL_UI_DELAY_MS = 1000L
        private const val MAX_ERROR_BODY_SIZE = 2048
        private const val ENABLE_UI_STOP_FALLBACK = true
        const val ACTION_INSTALL_RESULT = "com.droidrun.portal.action.INSTALL_RESULT"
        const val EXTRA_INSTALL_SUCCESS = "install_success"
        const val EXTRA_INSTALL_MESSAGE = "install_message"
        const val EXTRA_INSTALL_PACKAGE = "install_package"
        private const val INSTALL_NOTIFICATION_CHANNEL_ID = "install_result_channel"
        private const val INSTALL_NOTIFICATION_ID = 4001
    }

    private val installLock = Any()

    private fun getAvailableInternalBytes(): Long? {
        return try {
            StatFs(Environment.getDataDirectory().absolutePath).availableBytes
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read free space", e)
            null
        }
    }

    private class SizeLimitedInputStream(
        inputStream: InputStream,
        private val maxBytes: Long,
    ) : FilterInputStream(inputStream) {
        private var totalRead: Long = 0

        private fun onBytesRead(count: Int) {
            if (count <= 0) return
            totalRead += count.toLong()
            if (totalRead > maxBytes)
                throw IOException("APK exceeds max allowed size (${maxBytes} bytes)")

        }

        override fun read(): Int {
            val value = super.read()
            if (value != -1) onBytesRead(1)
            return value
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val count = super.read(b, off, len)
            if (count > 0) onBytesRead(count)
            return count
        }
    }

    // Queries
    fun ping() = ApiResponse.Success("pong")

    fun getTree(): ApiResponse {
        val elements = stateRepo.getVisibleElements()
        val json = elements.map { JsonBuilders.elementNodeToJson(it) }
        return ApiResponse.Success(JSONArray(json).toString())
    }

    fun getTreeFull(filter: Boolean): ApiResponse {
        val tree = stateRepo.getFullTree(filter)
            ?: return ApiResponse.Error("No active window or root filtered out")
        return ApiResponse.Success(tree.toString())
    }

    fun getPhoneState(): ApiResponse {
        val state = stateRepo.getPhoneState()
        return ApiResponse.Success(JsonBuilders.phoneStateToJson(state).toString())
    }

    fun getState(): ApiResponse {
        val elements = stateRepo.getVisibleElements()
        val treeJson = elements.map { JsonBuilders.elementNodeToJson(it) }
        val phoneStateJson = JsonBuilders.phoneStateToJson(stateRepo.getPhoneState())

        val combined = JSONObject().apply {
            put("a11y_tree", JSONArray(treeJson))
            put("phone_state", phoneStateJson)
        }
        return ApiResponse.Success(combined.toString())
    }

    fun getStateFull(filter: Boolean): ApiResponse {
        val tree = stateRepo.getFullTree(filter)
            ?: return ApiResponse.Error("No active window or root filtered out")
        val phoneStateJson = JsonBuilders.phoneStateToJson(stateRepo.getPhoneState())
        val deviceContext = stateRepo.getDeviceContext()

        val combined = JSONObject().apply {
            put("a11y_tree", tree)
            put("phone_state", phoneStateJson)
            put("device_context", deviceContext)
        }
        return ApiResponse.RawObject(combined)
    }

    fun getVersion() = ApiResponse.Success(appVersionProvider())


    fun getPackages(): ApiResponse {
        Log.d(TAG, "getPackages called")
        return try {
            val pm = getPackageManager()
            val mainIntent =
                Intent(android.content.Intent.ACTION_MAIN, null).apply {
                    addCategory(android.content.Intent.CATEGORY_LAUNCHER)
                }

            val resolvedApps: List<android.content.pm.ResolveInfo> =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.queryIntentActivities(mainIntent, PackageManager.ResolveInfoFlags.of(0L))
                } else {
                    @Suppress("DEPRECATION")
                    pm.queryIntentActivities(mainIntent, 0)
                }

            Log.d("ApiHandler", "Found ${resolvedApps.size} raw resolved apps")

            val arr = JSONArray()

            for (resolveInfo in resolvedApps) {
                try {
                    val pkgInfo = try {
                        pm.getPackageInfo(resolveInfo.activityInfo.packageName, 0)
                    } catch (e: PackageManager.NameNotFoundException) {
                        Log.w(
                            "ApiHandler",
                            "Package not found: ${resolveInfo.activityInfo.packageName}",
                        )
                        continue
                    }

                    val label = try {
                        resolveInfo.loadLabel(pm).toString()
                    } catch (e: Exception) {
                        Log.w(
                            "ApiHandler",
                            "Label load failed for ${pkgInfo.packageName}: ${e.message}",
                        )
                        // Fallback to package name if label load fails (Samsung resource error with ARzone or something)
                        pkgInfo.packageName
                    }

                    val appInfo = resolveInfo.activityInfo.applicationInfo
                    val obj = JSONObject()

                    obj.put("packageName", pkgInfo.packageName)
                    obj.put("label", label)
                    obj.put("versionName", pkgInfo.versionName ?: JSONObject.NULL)

                    val versionCode = pkgInfo.longVersionCode
                    obj.put("versionCode", versionCode)

                    val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    obj.put("isSystemApp", isSystem)

                    arr.put(obj)
                } catch (e: Exception) {
                    Log.w(
                        "ApiHandler",
                        "Skipping package ${resolveInfo.activityInfo.packageName}: ${e.message}",
                    )
                }
            }

            Log.d("ApiHandler", "Returning ${arr.length()} packages")

            ApiResponse.RawArray(arr)

        } catch (e: Exception) {
            Log.e("ApiHandler", "getPackages failed", e)
            ApiResponse.Error("Failed to enumerate launchable apps: ${e.message}")
        }
    }

    // Keyboard actions
    fun keyboardInput(base64Text: String, clear: Boolean): ApiResponse {
        val ime = getKeyboardIME()
        if (ime != null) {
            if (ime.inputB64Text(base64Text, clear)) {
                return ApiResponse.Success("input done via IME (clear=$clear)")
            }
        }

        // Fallback to accessibility services if IME is not active or failed
        try {
            val textBytes = android.util.Base64.decode(base64Text, android.util.Base64.DEFAULT)
            val text = String(textBytes, java.nio.charset.StandardCharsets.UTF_8)

            if (stateRepo.inputText(text, clear))
                return ApiResponse.Success("input done via Accessibility (clear=$clear)")

        } catch (e: Exception) {
            Log.e("ApiHandler", "Accessibility input fallback failed: ${e.message}")
        }

        return ApiResponse.Error("input failed (IME not active and Accessibility fallback failed)")
    }

    fun keyboardClear(): ApiResponse {
        val ime = getKeyboardIME()

        if (ime != null && ime.hasInputConnection()) {
            if (ime.clearText()) {
                return ApiResponse.Success("Text cleared via IME")
            }
            Log.w(TAG, "IME clearText() failed, falling back to Accessibility")
        }

        return if (stateRepo.inputText("", clear = true)) {
            ApiResponse.Success("Text cleared via Accessibility")
        } else {
            ApiResponse.Error("Clear failed (IME not active and Accessibility fallback failed)")
        }
    }

    /**
     * Helper to check if DroidrunKeyboardIME is both available and selected as the system default.
     * Matches the pattern used in ScrcpyControlChannel.
     */
    private fun isKeyboardImeActiveAndSelected(): Boolean {
        if (!DroidrunKeyboardIME.isAvailable()) return false
        val service = DroidrunAccessibilityService.getInstance() ?: return false
        return DroidrunKeyboardIME.isSelected(service)
    }

    fun keyboardKey(keyCode: Int): ApiResponse {
        // System navigation keys - use global actions (no IME needed)
        when (keyCode) {
            KeyEvent.KEYCODE_BACK -> return performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            KeyEvent.KEYCODE_HOME -> return performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            KeyEvent.KEYCODE_APP_SWITCH -> return performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
        }

        // ENTER key: use ACTION_IME_ENTER first, then fallback to newline insertion
        if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            val state = stateRepo.getPhoneState()
            val focusedNode = state.focusedElement

            try {
                if (focusedNode != null) {
                    if (focusedNode.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) {
                        return ApiResponse.Success("Enter performed via Accessibility")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Accessibility enter failed", e)
            } finally {
                try {
                    focusedNode?.recycle()
                } catch (_: Exception) {
                }
            }

            // Fallback: some multiline fields accept newline via ACTION_SET_TEXT
            return if (stateRepo.inputText("\n", clear = false))
                ApiResponse.Success("Newline inserted via Accessibility")
            else
                ApiResponse.Success("Enter handled (no focused element)")
        }

        // DEL key: manipulate text via accessibility
        if (keyCode == KeyEvent.KEYCODE_DEL) {
            val state = stateRepo.getPhoneState()
            val focusedNode = state.focusedElement

            val currentText: String?
            val hintText: String?
            try {
                currentText = focusedNode?.text?.toString()
                hintText = focusedNode?.hintText?.toString()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read focused text for delete", e)
                return ApiResponse.Success("Delete handled (could not read focused text)")
            } finally {
                try {
                    focusedNode?.recycle()
                } catch (_: Exception) {
                }
            }

            val effectiveText =
                if (!hintText.isNullOrEmpty() && currentText == hintText) "" else currentText.orEmpty()

            if (effectiveText.isEmpty())
                return ApiResponse.Success("Delete noop (field is empty)")

            val updatedText = effectiveText.dropLast(1)
            return if (stateRepo.inputText(updatedText, clear = true))
                ApiResponse.Success("Delete performed via Accessibility")
            else
                ApiResponse.Success("Delete handled")
        }

        // Forward DEL key: accessibility only
        if (keyCode == KeyEvent.KEYCODE_FORWARD_DEL) {
            val service = DroidrunAccessibilityService.getInstance()
                ?: return ApiResponse.Success("Forward delete handled (no service)")
            service.deleteText(1, forward = true)
            return ApiResponse.Success("Forward delete handled")
        }

        // TAB key: try IME if available and selected, else use accessibility
        // If nothing is focused, just succeed silently (noop)
        if (keyCode == KeyEvent.KEYCODE_TAB) {
            if (isKeyboardImeActiveAndSelected()) {
                val keyboard = DroidrunKeyboardIME.getInstance()
                if (keyboard != null && keyboard.sendKeyEventDirect(keyCode)) {
                    return ApiResponse.Success("Tab sent via IME")
                }
            }
            // Fallback to accessibility - if it fails (nothing focused), just succeed as noop
            stateRepo.inputText("\t", clear = false)
            return ApiResponse.Success("Tab handled")
        }

        // For other keycodes: try IME first, then convert to unicode character
        if (isKeyboardImeActiveAndSelected()) {
            val keyboard = DroidrunKeyboardIME.getInstance()
            if (keyboard != null && keyboard.sendKeyEventDirect(keyCode)) {
                return ApiResponse.Success("Key event sent via IME - code: $keyCode")
            }
        }

        // Fallback: convert keycode to character using KeyEvent
        val keyEvent = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
        val unicodeChar = keyEvent.getUnicodeChar(0)

        if (unicodeChar > 0) {
            val char = unicodeChar.toChar()
            return if (stateRepo.inputText(char.toString(), clear = false))
                ApiResponse.Success("Character '$char' inserted via Accessibility")
            else
                ApiResponse.Error("Failed to insert character")
        }

        return ApiResponse.Error("Unsupported key code: $keyCode (no unicode mapping and IME not available)")
    }

    // Overlay
    fun setOverlayOffset(offset: Int): ApiResponse {
        return if (stateRepo.setOverlayOffset(offset)) {
            ApiResponse.Success("Overlay offset updated to $offset")
        } else {
            ApiResponse.Error("Failed to update overlay offset")
        }
    }

    fun setOverlayVisible(visible: Boolean): ApiResponse {
        return if (stateRepo.setOverlayVisible(visible)) {
            ApiResponse.Success("Overlay visibility set to $visible")
        } else {
            ApiResponse.Error("Failed to set overlay visibility")
        }
    }

    fun setSocketPort(port: Int): ApiResponse {
        return if (stateRepo.updateSocketServerPort(port)) {
            ApiResponse.Success("Socket server port updated to $port")
        } else {
            ApiResponse.Error("Failed to update socket server port to $port (bind failed or invalid)")
        }
    }

    fun getScreenshot(hideOverlay: Boolean): ApiResponse {
        return try {
            val future = stateRepo.takeScreenshot(hideOverlay)
            // Wait up to a fixed timeout
            val result =
                future.get(SCREENSHOT_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)

            if (result.startsWith("error:")) {
                ApiResponse.Error(result.substring(7))
            } else {
                // Result is Base64 string from Service. 
                // decode it back to bytes to pass as Binary response.
                // In future, Service should return bytes directly to avoid this encode/decode cycle.
                // val bytes = android.util.Base64.decode(result, android.util.Base64.DEFAULT)

                // use base64 encoding to be compatible with json rpc 1.0.
                ApiResponse.Text(result)
            }
        } catch (e: java.util.concurrent.TimeoutException) {
            ApiResponse.Error("Screenshot timeout - operation took too long")
        } catch (e: Exception) {
            ApiResponse.Error("Failed to get screenshot: ${e.message}")
        }
    }

    // New Gesture Actions
    fun performTap(x: Int, y: Int): ApiResponse {
        return if (GestureController.tap(x, y)) {
            ApiResponse.Success("Tap performed at ($x, $y)")
        } else {
            ApiResponse.Error("Failed to perform tap at ($x, $y)")
        }
    }

    fun performSwipe(startX: Int, startY: Int, endX: Int, endY: Int, duration: Int): ApiResponse {
        return if (GestureController.swipe(startX, startY, endX, endY, duration)) {
            ApiResponse.Success("Swipe performed")
        } else {
            ApiResponse.Error("Failed to perform swipe")
        }
    }

    fun performGlobalAction(action: Int): ApiResponse {
        return if (GestureController.performGlobalAction(action)) {
            ApiResponse.Success("Global action $action performed")
        } else {
            ApiResponse.Error("Failed to perform global action $action")
        }
    }

    fun startApp(packageName: String, activityName: String? = null): ApiResponse {
        val service = DroidrunAccessibilityService.getInstance()
            ?: return ApiResponse.Error("Accessibility Service not available")

        return try {
            val intent = if (!activityName.isNullOrEmpty() && activityName != "null") {
                Intent().apply {
                    setClassName(
                        packageName,
                        if (activityName.startsWith(".")) packageName + activityName else activityName
                    )
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            } else {
                service.packageManager.getLaunchIntentForPackage(packageName)?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }

            if (intent != null) {
                service.startActivity(intent)
                ApiResponse.Success("Started app $packageName")
            } else {
                Log.e(
                    "ApiHandler",
                    "Could not create intent for $packageName - getLaunchIntentForPackage returned null. Trying fallback.",
                )

                try {
                    val fallbackIntent = Intent(Intent.ACTION_MAIN)
                    fallbackIntent.addCategory(Intent.CATEGORY_LAUNCHER)
                    fallbackIntent.setPackage(packageName)
                    fallbackIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

                    if (fallbackIntent.resolveActivity(service.packageManager) != null) {
                        service.startActivity(fallbackIntent)
                        ApiResponse.Success("Started app $packageName (fallback)")
                    } else {
                        ApiResponse.Error("Could not create intent for $packageName")
                    }
                } catch (e2: Exception) {
                    Log.e("ApiHandler", "Fallback start failed", e2)
                    ApiResponse.Error("Could not create intent for $packageName")
                }
            }
        } catch (e: Exception) {
            Log.e("ApiHandler", "Error starting app", e)
            ApiResponse.Error("Error starting app: ${e.message}")
        }
    }

    fun stopApp(packageName: String): ApiResponse {
        if (packageName.isBlank()) {
            return ApiResponse.Error("Missing required param: 'package'")
        }
        if (packageName == context.packageName) {
            return ApiResponse.Error("Refusing to stop Droidrun Portal")
        }

        val pm = getPackageManager()
        val appLabel = getAppLabel(packageName)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, 0)
            }
        } catch (e: PackageManager.NameNotFoundException) {
            return ApiResponse.Error("Package not installed: $packageName")
        }

        val granted =
            context.checkSelfPermission(Manifest.permission.KILL_BACKGROUND_PROCESSES) ==
                PackageManager.PERMISSION_GRANTED
        if (!granted) {
            return ApiResponse.Error("Missing permission: KILL_BACKGROUND_PROCESSES")
        }

        val phoneState = stateRepo.getPhoneState()
        if (phoneState.packageName == packageName) {
            GestureController.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        }

        var killError: String? = null
        val killSuccess = try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.killBackgroundProcesses(packageName)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping app", e)
            killError = e.message
            false
        }

        val uiResult = if (ENABLE_UI_STOP_FALLBACK) {
            tryForceStopViaSettings(packageName, appLabel)
        } else {
            ForceStopUiResult(attempted = false, success = false, reason = "ui_disabled")
        }

        val overallSuccess = if (uiResult.attempted) uiResult.success else killSuccess
        val resultJson = JSONObject().apply {
            put("message", "Stop requested for $packageName")
            put("killBackgroundProcesses", killSuccess)
            put("killError", killError ?: JSONObject.NULL)
            put("uiAttempted", uiResult.attempted)
            put("uiSuccess", uiResult.success)
            put("uiReason", uiResult.reason ?: JSONObject.NULL)
            put("overallSuccess", overallSuccess)
        }

        return if (overallSuccess) {
            ApiResponse.RawObject(resultJson)
        } else {
            ApiResponse.Error(resultJson.toString())
        }
    }

    fun getTime(): ApiResponse {
        return ApiResponse.Success(System.currentTimeMillis())
    }

    private fun getAppLabel(packageName: String): String? {
        return try {
            val pm = getPackageManager()
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get app label for $packageName: ${e.message}")
            null
        }
    }

    private data class ForceStopUiResult(
        val attempted: Boolean,
        val success: Boolean,
        val reason: String?,
    )

    private enum class ForceStopButtonState {
        CLICKED,
        DISABLED,
        NOT_FOUND,
        NOT_READY,
        CLICK_FAILED,
    }

    private fun tryForceStopViaSettings(packageName: String, appLabel: String?): ForceStopUiResult {
        val service = DroidrunAccessibilityService.getInstance()
            ?: return ForceStopUiResult(false, false, "service_unavailable")
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to open app settings for $packageName: ${e.message}")
            return ForceStopUiResult(
                attempted = true,
                success = false,
                reason = "open_settings_failed",
            )
        }

        val screenReady = waitForUiAction(
            timeoutMs = 3000L,
            intervalMs = 250L,
        ) {
            val elements = flattenElements(stateRepo.getVisibleElements())
            isForceStopConfirmDialogVisible(elements) ||
                isAppInfoScreenVisible(elements, appLabel, packageName)
        }
        if (!screenReady) {
            Log.d(TAG, "App info screen not ready for $packageName")
            logUiSnapshot("force_stop_screen_not_ready")
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            return ForceStopUiResult(attempted = true, success = false, reason = "screen_not_ready")
        }

        val dialogAlreadyVisible = waitForUiAction(
            timeoutMs = 1500L,
            intervalMs = 200L,
        ) { isForceStopConfirmDialogVisible() }
        if (dialogAlreadyVisible) {
            val confirmed = waitForUiAction(
                timeoutMs = 4000L,
                intervalMs = 250L,
            ) { tryClickForceStopConfirm() }
            if (!confirmed) {
                Log.d(TAG, "Force stop confirm dialog not detected for $packageName")
                logUiSnapshot("force_stop_confirm_not_found")
            }
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            return ForceStopUiResult(true, confirmed, if (confirmed) "confirm_clicked" else "confirm_not_found")
        }

        var buttonState = ForceStopButtonState.NOT_FOUND
        val buttonDeadline = SystemClock.elapsedRealtime() + 2500L
        while (SystemClock.elapsedRealtime() < buttonDeadline) {
            buttonState = evaluateForceStopButtonState(appLabel, packageName)
            if (buttonState == ForceStopButtonState.CLICKED ||
                buttonState == ForceStopButtonState.DISABLED
            ) {
                break
            }
            SystemClock.sleep(300L)
        }

        if (buttonState == ForceStopButtonState.DISABLED) {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            return ForceStopUiResult(
                attempted = true,
                success = true,
                reason = "force_stop_disabled",
            )
        }

        if (buttonState != ForceStopButtonState.CLICKED) {
            val confirmed = waitForUiAction(
                timeoutMs = 1000L,
                intervalMs = 200L,
            ) { tryClickForceStopConfirm() }
            if (confirmed) {
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                return ForceStopUiResult(
                    attempted = true,
                    success = true,
                    reason = "confirm_clicked",
                )
            }
            val openVisible = isOpenButtonVisible(flattenElements(stateRepo.getVisibleElements()))
            if (openVisible) {
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                return ForceStopUiResult(
                    attempted = true,
                    success = true,
                    reason = "force_stop_unavailable",
                )
            }
            Log.d(TAG, "Force stop button not found for $packageName")
            logUiSnapshot("force_stop_button_not_found")
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            return ForceStopUiResult(
                attempted = true,
                success = false,
                reason = "force_stop_button_not_found",
            )
        }

        val confirmed = waitForUiAction(
            timeoutMs = 4000L,
            intervalMs = 250L,
        ) { tryClickForceStopConfirm() }
        if (!confirmed) {
            Log.d(TAG, "Force stop confirm dialog not detected for $packageName")
            logUiSnapshot("force_stop_confirm_not_found")
        }
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        return ForceStopUiResult(true, confirmed, if (confirmed) "confirm_clicked" else "confirm_not_found")
    }

    private fun evaluateForceStopButtonState(
        appLabel: String?,
        packageName: String,
    ): ForceStopButtonState {
        val elements = flattenElements(stateRepo.getVisibleElements())
        if (isForceStopConfirmDialogVisible(elements)) return ForceStopButtonState.NOT_READY
        if (!isAppInfoScreenVisible(elements, appLabel, packageName)) return ForceStopButtonState.NOT_READY
        val button = findForceStopButton(elements) ?: return ForceStopButtonState.NOT_FOUND
        val info = button.nodeInfo
        if (!info.isEnabled) return ForceStopButtonState.DISABLED
        return if (info.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            ForceStopButtonState.CLICKED
        } else {
            ForceStopButtonState.CLICK_FAILED
        }
    }

    private fun tryClickForceStopConfirm(): Boolean {
        val elements = flattenElements(stateRepo.getVisibleElements())
        if (!isForceStopConfirmDialogVisible(elements)) return false
        val dialogButton = findDialogPositiveButton(elements)
        val info = dialogButton?.nodeInfo
        if (info != null && info.isEnabled) {
            return info.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        if (!isEnglishLocale()) return false
        val button = findBestClickableMatch(
            elements,
            listOf("force stop", "force-stop", "ok", "yes", "confirm"),
        )
        val fallbackInfo = button?.nodeInfo ?: return false
        if (!fallbackInfo.isEnabled) return false
        return fallbackInfo.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun isForceStopConfirmDialogVisible(): Boolean {
        val elements = flattenElements(stateRepo.getVisibleElements())
        return isForceStopConfirmDialogVisible(elements)
    }

    private fun isForceStopConfirmDialogVisible(
        elements: List<com.droidrun.portal.model.ElementNode>,
    ): Boolean {
        var hasDialogText = false
        var hasButtons = false
        var hasButtonPanel = false
        for (element in elements) {
            val viewId = element.nodeInfo.viewIdResourceName.orEmpty()
            if (viewId == "com.android.settings:id/alertTitle" ||
                viewId == "android:id/alertTitle" ||
                viewId == "android:id/message"
            ) {
                hasDialogText = true
            }
            if (viewId == "android:id/button1" || viewId == "android:id/button2") {
                hasButtons = true
            }
            if (viewId == "com.android.settings:id/buttonPanel" ||
                viewId == "android:id/buttonPanel"
            ) {
                hasButtonPanel = true
            }
        }
        if (hasButtons && (hasDialogText || hasButtonPanel)) {
            return true
        }

        val dialogButtons = findDialogButtonRow(elements)
        if (dialogButtons.size < 2) return false

        val screenWidth = elements.maxOfOrNull { it.rect.right }?.toFloat()?.coerceAtLeast(1f) ?: return false
        val screenHeight = elements.maxOfOrNull { it.rect.bottom }?.toFloat()?.coerceAtLeast(1f) ?: return false
        var left = dialogButtons.minOf { it.rect.left }
        var top = dialogButtons.minOf { it.rect.top }
        var right = dialogButtons.maxOf { it.rect.right }
        var bottom = dialogButtons.maxOf { it.rect.bottom }

        val buttonsTop = top
        val horizontalMargin = (screenWidth * 0.08f).toInt()
        val titleCandidates = elements.filter { element ->
            if (!element.className.contains("TextView", ignoreCase = true)) return@filter false
            if (element.text.isBlank()) return@filter false
            if (element.rect.bottom > buttonsTop) return@filter false
            val overlaps =
                element.rect.right >= left - horizontalMargin &&
                    element.rect.left <= right + horizontalMargin
            overlaps
        }
        if (titleCandidates.isEmpty()) return false
        left = minOf(left, titleCandidates.minOf { it.rect.left })
        top = minOf(top, titleCandidates.minOf { it.rect.top })
        right = maxOf(right, titleCandidates.maxOf { it.rect.right })
        bottom = maxOf(bottom, titleCandidates.maxOf { it.rect.bottom })

        val heightRatio = (bottom - top).toFloat() / screenHeight
        val widthRatio = (right - left).toFloat() / screenWidth
        val leftMargin = left.toFloat() / screenWidth
        val rightMargin = (screenWidth - right).toFloat() / screenWidth
        if (heightRatio !in 0.12f..0.6f) return false
        if (widthRatio !in 0.3f..0.95f) return false
        if (leftMargin < 0.05f || rightMargin < 0.05f) return false

        return true
    }

    private fun findDialogButtonRow(
        elements: List<com.droidrun.portal.model.ElementNode>,
    ): List<com.droidrun.portal.model.ElementNode> {
        val screenWidth = elements.maxOfOrNull { it.rect.right }?.toFloat()?.coerceAtLeast(1f) ?: return emptyList()
        val screenHeight = elements.maxOfOrNull { it.rect.bottom }?.toFloat()?.coerceAtLeast(1f) ?: return emptyList()
        val minButtonWidth = screenWidth * 0.12f
        val minButtonHeight = screenHeight * 0.03f
        val candidates = elements.filter { element ->
            val info = element.nodeInfo
            val width = element.rect.width().toFloat()
            val height = element.rect.height().toFloat()
            val isButtonClass = element.className.contains("Button", ignoreCase = true)
            (info.isClickable || isButtonClass) && width >= minButtonWidth && height >= minButtonHeight
        }
        if (candidates.isEmpty()) return emptyList()
        val tolerance = screenHeight * 0.04f
        val rows = mutableListOf<MutableList<com.droidrun.portal.model.ElementNode>>()
        for (candidate in candidates) {
            val centerY = candidate.rect.centerY().toFloat()
            val row = rows.firstOrNull { group ->
                val groupCenter = group.first().rect.centerY().toFloat()
                kotlin.math.abs(groupCenter - centerY) <= tolerance
            }
            if (row != null) {
                row.add(candidate)
            } else {
                rows.add(mutableListOf(candidate))
            }
        }
        val bestRow = rows
            .filter { it.size >= 2 }
            .maxByOrNull { row ->
                val minX = row.minOf { it.rect.left }
                val maxX = row.maxOf { it.rect.right }
                val span = (maxX - minX).toFloat()
                val centerY = row.first().rect.centerY().toFloat()
                span + centerY
            }
        return bestRow ?: emptyList()
    }

    private fun isAppInfoScreenVisible(
        elements: List<com.droidrun.portal.model.ElementNode>,
        appLabel: String?,
        packageName: String,
    ): Boolean {
        if (isForceStopConfirmDialogVisible(elements)) return true
        val hasForceStopText = if (isEnglishLocale()) {
            elements.any { element ->
                val text = element.text.lowercase()
                val desc = element.nodeInfo.contentDescription?.toString()?.lowercase().orEmpty()
                text.contains("force stop") || desc.contains("force stop")
            }
        } else {
            false
        }
        val labelVisible = isAppLabelVisible(elements, appLabel, packageName)
        val hasForceStopButton = findForceStopButton(elements) != null
        if (!labelVisible) return false
        return if (isEnglishLocale()) {
            hasForceStopText && hasForceStopButton
        } else {
            hasForceStopButton
        }
    }

    private fun isAppLabelVisible(
        elements: List<com.droidrun.portal.model.ElementNode>,
        appLabel: String?,
        packageName: String,
    ): Boolean {
        val label = appLabel?.trim().orEmpty().lowercase()
        val minLength = 3
        return elements.any { element ->
            val text = element.text.lowercase()
            val desc = element.nodeInfo.contentDescription?.toString()?.lowercase().orEmpty()
            val labelMatch =
                label.length >= minLength &&
                    (text.contains(label) || desc.contains(label))
            val packageMatch = text.contains(packageName) || desc.contains(packageName)
            labelMatch || packageMatch
        }
    }

    private fun findForceStopButton(
        elements: List<com.droidrun.portal.model.ElementNode>,
    ): com.droidrun.portal.model.ElementNode? {
        val idMatches = listOf(
            "force_stop",
            "force_stop_button",
            "button_force_stop",
            "forceStop",
        )
        for (element in elements) {
            val viewId = element.nodeInfo.viewIdResourceName.orEmpty()
            if (idMatches.any { token -> viewId.contains(token, ignoreCase = true) }) {
                return element
            }
        }
        if (isEnglishLocale()) {
            val textMatch = findClickableForText(
                elements,
                listOf("force stop", "force-stop"),
            )
            if (textMatch != null) return textMatch
        }
        val settingsButton = findSettingsActionButton(elements)
        if (settingsButton != null) return settingsButton
        val actionRowButton = findActionRowForceStopFallback(elements)
        if (actionRowButton != null) return actionRowButton
        if (!isEnglishLocale()) return null
        return findBestClickableMatch(elements, listOf("force stop", "force-stop"))
    }

    private fun findClickableForText(
        elements: List<com.droidrun.portal.model.ElementNode>,
        needles: List<String>,
    ): com.droidrun.portal.model.ElementNode? {
        val matches = elements.filter { element ->
            val text = element.text.lowercase()
            val desc = element.nodeInfo.contentDescription?.toString()?.lowercase().orEmpty()
            needles.any { needle -> text.contains(needle) || desc.contains(needle) }
        }
        for (match in matches) {
            val ancestor = findClickableAncestor(match)
            if (ancestor != null) return ancestor
            val containing = elements.filter { element ->
                element.nodeInfo.isClickable && element.rect.contains(match.rect)
            }
            if (containing.isNotEmpty()) {
                return containing.minBy { it.rect.width() * it.rect.height() }
            }
        }
        return null
    }

    private fun findClickableAncestor(
        node: com.droidrun.portal.model.ElementNode,
        maxDepth: Int = 6,
    ): com.droidrun.portal.model.ElementNode? {
        var current = node.parent
        var depth = 0
        while (current != null && depth < maxDepth) {
            if (current.nodeInfo.isClickable) return current
            current = current.parent
            depth++
        }
        return null
    }

    private fun findSettingsActionButton(
        elements: List<com.droidrun.portal.model.ElementNode>,
    ): com.droidrun.portal.model.ElementNode? {
        val candidates = mutableMapOf<Int, com.droidrun.portal.model.ElementNode>()
        for (element in elements) {
            val viewId = element.nodeInfo.viewIdResourceName.orEmpty()
            if (viewId.startsWith("android:id/button")) continue
            val index = settingsButtonIndex(viewId) ?: continue
            candidates[index] = element
        }
        if (candidates.isEmpty()) return null
        val maxIndex = candidates.keys.maxOrNull() ?: return null
        return if (maxIndex >= 3) candidates[maxIndex] else null
    }

    private fun findActionRowForceStopFallback(
        elements: List<com.droidrun.portal.model.ElementNode>,
    ): com.droidrun.portal.model.ElementNode? {
        if (elements.isEmpty()) return null
        val screenWidth = elements.maxOf { it.rect.right }.toFloat().coerceAtLeast(1f)
        val screenHeight = elements.maxOf { it.rect.bottom }.toFloat().coerceAtLeast(1f)
        val minWidth = screenWidth * 0.2f
        val minHeight = screenHeight * 0.05f
        val candidates = elements.filter { element ->
            val info = element.nodeInfo
            if (!info.isClickable) return@filter false
            val width = element.rect.width().toFloat()
            val height = element.rect.height().toFloat()
            width >= minWidth && height >= minHeight
        }
        if (candidates.isEmpty()) return null
        val tolerance = screenHeight * 0.08f
        val groups = mutableListOf<MutableList<com.droidrun.portal.model.ElementNode>>()
        for (candidate in candidates) {
            val centerY = candidate.rect.centerY().toFloat()
            val group = groups.firstOrNull { group ->
                val groupCenter = group.first().rect.centerY().toFloat()
                kotlin.math.abs(groupCenter - centerY) <= tolerance
            }
            if (group != null) {
                group.add(candidate)
            } else {
                groups.add(mutableListOf(candidate))
            }
        }
        val bestGroup = groups
            .filter { it.size >= 3 }
            .maxByOrNull { group ->
                val minX = group.minOf { it.rect.left }
                val maxX = group.maxOf { it.rect.right }
                val span = (maxX - minX).toFloat()
                span
            } ?: return null
        val minX = bestGroup.minOf { it.rect.left }
        val maxX = bestGroup.maxOf { it.rect.right }
        val span = (maxX - minX).toFloat()
        if (span < screenWidth * 0.6f) return null
        return bestGroup.maxByOrNull { it.rect.right }
    }

    private fun settingsButtonIndex(viewId: String): Int? {
        val prefix = ":id/button"
        val idx = viewId.lastIndexOf(prefix)
        if (idx == -1) return null
        val suffix = viewId.substring(idx + prefix.length)
        if (suffix.isEmpty()) return null
        val digit = suffix.trim().toIntOrNull() ?: return null
        return if (digit in 1..4) digit else null
    }

    private fun isOpenButtonVisible(
        elements: List<com.droidrun.portal.model.ElementNode>,
    ): Boolean {
        val idMatches = listOf("launch", "open")
        for (element in elements) {
            val info = element.nodeInfo
            val viewId = info.viewIdResourceName.orEmpty()
            if (idMatches.any { token -> viewId.contains(token, ignoreCase = true) }) {
                return true
            }
            if (isEnglishLocale()) {
                val text = element.text.lowercase()
                val desc = info.contentDescription?.toString()?.lowercase().orEmpty()
                if (text == "open" || desc == "open") return true
            }
        }
        return false
    }

    private fun isEnglishLocale(): Boolean {
        return Locale.getDefault().language.equals("en", ignoreCase = true)
    }

    private fun findDialogPositiveButton(
        elements: List<com.droidrun.portal.model.ElementNode>,
    ): com.droidrun.portal.model.ElementNode? {
        val byId = findBestClickableById(
            elements,
            listOf("android:id/button1", "com.android.settings:id/button1"),
        )
        if (byId != null) return byId

        val row = findDialogButtonRow(elements)
        val rightmost = row.maxByOrNull { it.rect.right } ?: return null
        val ancestor = if (rightmost.nodeInfo.isClickable) null else findClickableAncestor(rightmost)
        return ancestor ?: rightmost
    }

    private fun waitForUiAction(
        timeoutMs: Long,
        intervalMs: Long,
        action: () -> Boolean,
    ): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (action()) return true
            SystemClock.sleep(intervalMs)
        }
        return false
    }

    private fun findBestClickableMatch(
        elements: List<com.droidrun.portal.model.ElementNode>,
        needles: List<String>,
    ): com.droidrun.portal.model.ElementNode? {
        var best: com.droidrun.portal.model.ElementNode? = null
        var bestScore = Int.MAX_VALUE
        for (element in elements) {
            val info = element.nodeInfo
            if (!info.isClickable) continue
            val text = element.text.lowercase()
            val desc = info.contentDescription?.toString()?.lowercase().orEmpty()
            for (needle in needles) {
                val score = scoreStringMatch(text, needle) ?: scoreStringMatch(desc, needle)
                if (score != null && score < bestScore) {
                    best = element
                    bestScore = score
                }
            }
        }
        return best
    }

    private fun findBestClickableById(
        elements: List<com.droidrun.portal.model.ElementNode>,
        viewIdMatches: List<String>,
    ): com.droidrun.portal.model.ElementNode? {
        for (element in elements) {
            val info = element.nodeInfo
            if (!info.isClickable) continue
            val viewId = info.viewIdResourceName ?: continue
            if (viewIdMatches.any { match ->
                    viewId.equals(match, ignoreCase = true) || viewId.endsWith(match)
                }
            ) {
                return element
            }
        }
        return null
    }

    private fun flattenElements(
        elements: List<com.droidrun.portal.model.ElementNode>
    ): List<com.droidrun.portal.model.ElementNode> {
        val all = mutableListOf<com.droidrun.portal.model.ElementNode>()
        fun collect(node: com.droidrun.portal.model.ElementNode) {
            all.add(node)
            node.children.forEach { child -> collect(child) }
        }
        elements.forEach { root -> collect(root) }
        return all
    }

    private fun logUiSnapshot(reason: String) {
        val elements = flattenElements(stateRepo.getVisibleElements())
        val total = elements.size
        val maxLines = 80
        val sb = StringBuilder()
        var lines = 0
        for (element in elements) {
            val text = element.text.trim()
            val desc = element.nodeInfo.contentDescription?.toString()?.trim().orEmpty()
            val viewId = element.nodeInfo.viewIdResourceName?.trim().orEmpty()
            if (text.isEmpty() && desc.isEmpty() && viewId.isEmpty()) continue
            sb.append("[").append(element.className).append("] ")
            if (text.isNotEmpty()) sb.append("text='").append(text).append("' ")
            if (desc.isNotEmpty()) sb.append("desc='").append(desc).append("' ")
            if (viewId.isNotEmpty()) sb.append("id='").append(viewId).append("' ")
            sb.append("rect=").append(element.rect.toShortString())
            sb.append('\n')
            lines++
            if (lines >= maxLines) break
        }
        Log.d(TAG, "UI snapshot reason=$reason total=$total listed=$lines\n$sb")
    }

    private fun scoreStringMatch(haystack: String, needle: String): Int? {
        if (haystack.isEmpty()) return null
        if (haystack == needle) return 0
        if (haystack.contains(needle)) return 10 + (haystack.length - needle.length)
        return null
    }

    fun installApp(
        apkStream: InputStream,
        hideOverlay: Boolean = false,
        expectedSizeBytes: Long = -1L,
    ): ApiResponse {
        return try {
            if (!context.packageManager.canRequestPackageInstalls()) {
                Log.e(
                    TAG,
                    "Install permission not granted (canRequestPackageInstalls = false)"
                )
                // Show permission dialog to guide the user
                showInstallPermissionDialog()
                return ApiResponse.Error("Install permission denied. Please enable 'Install unknown apps' for Droidrun Portal in Settings.")
            }

            if (expectedSizeBytes > MAX_APK_BYTES) {
                return ApiResponse.Error("APK too large: $expectedSizeBytes bytes (max $MAX_APK_BYTES)")
            }

            if (expectedSizeBytes > 0) {
                val availableBytes = getAvailableInternalBytes()
                if (availableBytes != null) {
                    val requiredBytes = expectedSizeBytes + INSTALL_FREE_SPACE_MARGIN_BYTES
                    if (availableBytes < requiredBytes) {
                        return ApiResponse.Error(
                            "Insufficient storage: need ~$requiredBytes bytes, have $availableBytes bytes",
                        )
                    }
                }
            }

            val packageInstaller = getPackageManager().packageInstaller
            val params =
                PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            val sessionId = packageInstaller.createSession(params)
            val session = packageInstaller.openSession(sessionId)

            session.use {
                val totalBytes = writeApkToSession(it, "base_apk", apkStream, expectedSizeBytes)
                Log.i("ApiHandler", "Written $totalBytes decoded bytes to install session")
                commitInstallSession(sessionId, it, hideOverlay)
            }
        } catch (e: Exception) {
            Log.e("ApiHandler", "Install failed", e)
            ApiResponse.Error("Install exception: ${e.message}")
        }
    }

    private fun writeApkToSession(
        session: PackageInstaller.Session,
        entryName: String,
        apkStream: InputStream,
        expectedSizeBytes: Long,
    ): Long {
        val writeSize = if (expectedSizeBytes > 0) expectedSizeBytes else -1L
        val out = session.openWrite(entryName, 0, writeSize)
        var totalBytes = 0L
        apkStream.use { rawInput ->
            val input = SizeLimitedInputStream(rawInput, MAX_APK_BYTES)
            val buffer = ByteArray(65536)
            var c: Int
            while (input.read(buffer).also { c = it } != -1) {
                out.write(buffer, 0, c)
                totalBytes += c
            }
        }
        session.fsync(out)
        out.close()
        return totalBytes
    }

    private fun commitInstallSession(
        sessionId: Int,
        session: PackageInstaller.Session,
        hideOverlay: Boolean,
    ): ApiResponse {
        val latch = CountDownLatch(1)
        var success = false
        var errorMsg = ""
        var confirmationLaunched = false
        var installedPackageName: String? = null
        val wasOverlayVisible = stateRepo.isOverlayVisible()
        val shouldHideOverlay = hideOverlay && wasOverlayVisible
        var receiverRegistered = false

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                val status =
                    intent?.getIntExtra(
                        PackageInstaller.EXTRA_STATUS,
                        PackageInstaller.STATUS_FAILURE,
                    )
                val message = intent?.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                val packageName = intent?.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)
                if (!packageName.isNullOrBlank()) installedPackageName = packageName

                Log.d("ApiHandler", "Install Status Received: $status, Message: $message")

                if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    val confirmationIntent =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent?.getParcelableExtra(
                                Intent.EXTRA_INTENT,
                                Intent::class.java,
                            )
                        } else {
                            @Suppress("DEPRECATION")
                            (intent?.getParcelableExtra(Intent.EXTRA_INTENT))
                        }

                    if (confirmationIntent == null) {
                        errorMsg = "Install confirmation intent missing"
                        latch.countDown()
                        return
                    }

                    if (!confirmationLaunched) {
                        confirmationIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        try {
                            AutoAcceptGate.armInstall()
                            context.startActivity(confirmationIntent)
                        } catch (e: Exception) {
                            errorMsg = "Failed to launch install confirmation: ${e.message}"
                            latch.countDown()
                        }
                    }
                    return
                }

                if (status == PackageInstaller.STATUS_SUCCESS) {
                    success = true
                    latch.countDown()
                    return
                }

                errorMsg = message ?: "Unknown error (Status Code: $status)"
                if (status == PackageInstaller.STATUS_FAILURE_INVALID) errorMsg += " [INVALID]"
                if (status == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE) errorMsg += " [INCOMPATIBLE]"
                if (status == PackageInstaller.STATUS_FAILURE_STORAGE) errorMsg += " [STORAGE]"
                latch.countDown()
            }
        }

        val action = "com.droidrun.portal.INSTALL_COMPLETE_${sessionId}"
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            sessionId,
            Intent(action).setPackage(context.packageName),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(
                    receiver,
                    IntentFilter(action),
                    Context.RECEIVER_NOT_EXPORTED,
                )
            } else {
                @Suppress("DEPRECATION")
                context.registerReceiver(receiver, IntentFilter(action))
            }
            receiverRegistered = true

            if (shouldHideOverlay) {
                Log.i(TAG, "Hiding overlay to prevent Tapjacking protection...")
                stateRepo.setOverlayVisible(false)
            }

            // bring the app to the foreground
            Log.i(TAG, "Bringing app to foreground for install prompt...")
            val foregroundIntent =
                Intent(context, com.droidrun.portal.ui.MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                }
            context.startActivity(foregroundIntent)

            try {
                Thread.sleep(INSTALL_UI_DELAY_MS)
            } catch (ignored: InterruptedException) {
            }

            Log.i(TAG, "Committing install session...")
            session.commit(pendingIntent.intentSender)

            val completed =
                latch.await(3, TimeUnit.MINUTES) // timeout for user interaction
            if (!completed && errorMsg.isBlank()) {
                errorMsg = "Timed out waiting for install result"
            }
        } finally {
            AutoAcceptGate.disarmInstall()
            if (receiverRegistered) {
                try {
                    context.unregisterReceiver(receiver)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to unregister install receiver", e)
                }
            }
            if (shouldHideOverlay) {
                stateRepo.setOverlayVisible(wasOverlayVisible)
            }
        }

        val packageSuffix = installedPackageName?.let { " ($it)" } ?: ""
        val response = if (success) {
            ApiResponse.Success("App installed successfully")
        } else {
            ApiResponse.Error("Install failed: $errorMsg")
        }

        val message = if (success) {
            "App installed successfully$packageSuffix"
        } else {
            "Install failed$packageSuffix: $errorMsg"
        }

        notifyInstallResult(success, message, installedPackageName)
        return response
    }

    private fun notifyInstallResult(success: Boolean, message: String, packageName: String?) {
        try {
            val intent = Intent(ACTION_INSTALL_RESULT)
                .setPackage(context.packageName)
                .putExtra(EXTRA_INSTALL_SUCCESS, success)
                .putExtra(EXTRA_INSTALL_MESSAGE, message)
                .putExtra(EXTRA_INSTALL_PACKAGE, packageName ?: "")
            context.sendBroadcast(intent)

            if (!AppVisibilityTracker.isInForeground()) {
                showInstallNotification(success, message)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to broadcast install result", e)
        }
    }

    private fun showInstallNotification(success: Boolean, message: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            INSTALL_NOTIFICATION_CHANNEL_ID,
            "Install Results",
            NotificationManager.IMPORTANCE_HIGH,
        )
        nm.createNotificationChannel(channel)

        val icon = if (success) {
            android.R.drawable.stat_sys_download_done
        } else {
            android.R.drawable.stat_notify_error
        }

        val notification = NotificationCompat.Builder(context, INSTALL_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(icon)
            .setContentTitle("App install")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        nm.notify(INSTALL_NOTIFICATION_ID, notification)
    }

    private fun installSplitApksFromUrls(urls: List<String>, hideOverlay: Boolean): ApiResponse {
        val invalidUrl = urls.firstOrNull { url ->
            val scheme = url.toUri().scheme?.lowercase()
            scheme != "https" && scheme != "http"
        }
        if (invalidUrl != null) {
            val scheme = invalidUrl.toUri().scheme?.lowercase()
            return ApiResponse.Error("Unsupported URL scheme: ${scheme ?: "null"}")
        }

        val packageInstaller = getPackageManager().packageInstaller
        val params =
            PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = packageInstaller.createSession(params)
        val session = packageInstaller.openSession(sessionId)

        session.use {
            var totalBytes = 0L
            urls.forEachIndexed { index, urlString ->
                val connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    connectTimeout = 15_000
                    readTimeout = 60_000
                    requestMethod = "GET"
                    setRequestProperty(
                        "Accept",
                        "application/vnd.android.package-archive,application/octet-stream,*/*",
                    )
                }

                try {
                    val code = connection.responseCode
                    if (code !in 200..299) {
                        val errorBody =
                            connection.errorStream?.bufferedReader()?.use { reader ->
                                val text = reader.readText()
                                if (text.length > MAX_ERROR_BODY_SIZE) text.take(
                                    MAX_ERROR_BODY_SIZE
                                ) else text
                            }
                        session.abandon()
                        return ApiResponse.Error(
                            buildString {
                                append("Download failed: HTTP $code")
                                connection.responseMessage?.let { msg ->
                                    if (msg.isNotBlank()) append(" $msg")
                                }
                                if (!errorBody.isNullOrBlank()) append(": $errorBody")
                            },
                        )
                    }

                    val contentLength = connection.contentLengthLong
                    if (contentLength > MAX_APK_BYTES) {
                        session.abandon()
                        return ApiResponse.Error(
                            "APK too large: $contentLength bytes (max $MAX_APK_BYTES)",
                        )
                    }

                    val availableBytes = getAvailableInternalBytes()
                    if (availableBytes != null) {
                        val requiredBytes = when {
                            contentLength > 0 -> contentLength + INSTALL_FREE_SPACE_MARGIN_BYTES
                            else -> INSTALL_FREE_SPACE_MARGIN_BYTES
                        }
                        if (availableBytes < requiredBytes) {
                            session.abandon()
                            return ApiResponse.Error(
                                "Insufficient storage: need ~$requiredBytes bytes, have $availableBytes bytes",
                            )
                        }
                    }

                    val entryName = "apk_${index}.apk"
                    val writtenBytes = connection.inputStream.use { stream ->
                        writeApkToSession(session, entryName, stream, contentLength)
                    }
                    totalBytes += writtenBytes
                } finally {
                    try {
                        connection.disconnect()
                    } catch (_: Exception) {
                    }
                }
            }

            Log.i("ApiHandler", "Written $totalBytes decoded bytes to install session")
            return commitInstallSession(sessionId, it, hideOverlay)
        }
    }

    fun installFromUrls(urls: List<String>, hideOverlay: Boolean = false): ApiResponse {
        if (urls.isEmpty()) return ApiResponse.Error("No APK URLs provided")

        if (!context.packageManager.canRequestPackageInstalls()) {
            Log.e(TAG, "Install permission not granted (canRequestPackageInstalls = false)")
            // Show permission dialog to guide the user
            showInstallPermissionDialog()
            return ApiResponse.Error(
                "Install permission denied. Please enable 'Install unknown apps' for Droidrun Portal in Settings.",
            )
        }

        val results = JSONArray()
        var successCount = 0
        val uniqueUrls = urls.map { it.trim() }.filter { it.isNotEmpty() }.distinct()

        synchronized(installLock) {
            if (uniqueUrls.size > 1) {
                val installResponse = installSplitApksFromUrls(uniqueUrls, hideOverlay)
                val success = installResponse is ApiResponse.Success
                val message = when (installResponse) {
                    is ApiResponse.Success -> installResponse.data.toString()
                    is ApiResponse.Error -> installResponse.message
                    else -> "Unexpected install response: ${installResponse.javaClass.simpleName}"
                }

                for (urlString in uniqueUrls) {
                    val result = JSONObject().apply { put("url", urlString) }
                    if (success) {
                        successCount += 1
                        result.put("success", true)
                        result.put("message", message)
                    } else {
                        result.put("success", false)
                        result.put("error", message)
                    }
                    results.put(result)
                }
            } else {
                for (urlString in uniqueUrls) {
                    val result = JSONObject().apply { put("url", urlString) }

                    try {
                        val uri = urlString.toUri()
                        val scheme = uri.scheme?.lowercase()
                        if (scheme != "https" && scheme != "http") {
                            result.put("success", false)
                            result.put("error", "Unsupported URL scheme: ${scheme ?: "null"}")
                            results.put(result)
                            continue
                        }

                        val connection =
                            (URL(urlString).openConnection() as HttpURLConnection).apply {
                                instanceFollowRedirects = true
                                connectTimeout = 15_000
                                readTimeout = 60_000
                                requestMethod = "GET"
                                setRequestProperty(
                                    "Accept",
                                    "application/vnd.android.package-archive,application/octet-stream,*/*",
                                )
                            }

                        try {
                            val code = connection.responseCode
                            if (code !in 200..299) {
                                val errorBody =
                                    connection.errorStream?.bufferedReader()?.use { reader ->
                                        val text = reader.readText()
                                        if (text.length > MAX_ERROR_BODY_SIZE) text.take(
                                            MAX_ERROR_BODY_SIZE
                                        ) else text
                                    }
                                result.put("success", false)
                                result.put(
                                    "error",
                                    buildString {
                                        append("Download failed: HTTP $code")
                                        connection.responseMessage?.let { msg ->
                                            if (msg.isNotBlank()) append(" $msg")
                                        }
                                        if (!errorBody.isNullOrBlank()) append(": $errorBody")
                                    },
                                )
                                results.put(result)
                                continue
                            }

                            val contentLength = connection.contentLengthLong

                            if (contentLength > MAX_APK_BYTES) {
                                result.put("success", false)
                                result.put(
                                    "error",
                                    "APK too large: $contentLength bytes (max $MAX_APK_BYTES)",
                                )
                                results.put(result)
                                continue
                            }

                            val availableBytes = getAvailableInternalBytes()
                            if (availableBytes != null) {
                                val requiredBytes = when {
                                    contentLength > 0 -> contentLength + INSTALL_FREE_SPACE_MARGIN_BYTES
                                    else -> INSTALL_FREE_SPACE_MARGIN_BYTES
                                }
                                if (availableBytes < requiredBytes) {
                                    result.put("success", false)
                                    result.put(
                                        "error",
                                        "Insufficient storage: need ~$requiredBytes bytes, have $availableBytes bytes",
                                    )
                                    results.put(result)
                                    continue
                                }
                            }

                            val installResponse =
                                connection.inputStream.use { stream ->
                                    installApp(
                                        stream,
                                        hideOverlay,
                                        expectedSizeBytes = contentLength
                                    )
                                }

                            when (installResponse) {
                                is ApiResponse.Success -> {
                                    successCount += 1
                                    result.put("success", true)
                                    result.put("message", installResponse.data.toString())
                                }

                                is ApiResponse.Error -> {
                                    result.put("success", false)
                                    result.put("error", installResponse.message)
                                }

                                else -> {
                                    result.put("success", false)
                                    result.put(
                                        "error",
                                        "Unexpected install response: ${installResponse.javaClass.simpleName}",
                                    )
                                }
                            }
                        } finally {
                            try {
                                connection.disconnect()
                            } catch (_: Exception) {
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Install from URL failed: $urlString", e)
                        result.put("success", false)
                        result.put("error", e.message ?: "Install from URL failed")
                    }

                    results.put(result)
                }
            }
        }

        val summary = JSONObject().apply {
            put("overallSuccess", successCount == uniqueUrls.size)
            put("successCount", successCount)
            put("failureCount", uniqueUrls.size - successCount)
            put("results", results)
        }

        return ApiResponse.RawObject(summary)
    }

    fun startStream(params: JSONObject): ApiResponse {
        val width = params.optInt("width", 720).coerceIn(144, 1920)
        val height = params.optInt("height", 1280).coerceIn(256, 3840)
        val fps = params.optInt("fps", 30).coerceIn(1, 60)
        val sessionId = params.optString("sessionId")
        val waitForOffer = params.optBoolean("waitForOffer", false)
        val manager = WebRtcManager.getInstance(context)
        manager.setStreamRequestId(sessionId)
        params.optJSONArray("iceServers")?.let {
            manager.setPendingIceServers(parseIceServers(it))
        }

        if (manager.isCaptureActive()) {
            return try {
                manager.startStreamWithExistingCapture(width, height, fps, waitForOffer)
                ApiResponse.Success("reusing_capture")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to reuse active capture", e)
                ApiResponse.Error("stream_restart_failed: ${e.message}")
            }
        }

        val intent =
            Intent(context, com.droidrun.portal.ui.ScreenCaptureActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(ScreenCaptureService.EXTRA_WIDTH, width)
                putExtra(ScreenCaptureService.EXTRA_HEIGHT, height)
                putExtra(ScreenCaptureService.EXTRA_FPS, fps)
                putExtra(ScreenCaptureService.EXTRA_WAIT_FOR_OFFER, waitForOffer)
            }

        try {
            context.startActivity(intent)
            return ApiResponse.Success("prompting_user")
        } catch (e: Exception) {
            Log.w(
                TAG,
                "Failed to start ScreenCaptureActivity directly: ${e.message}. Trying notification trampoline."
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val notificationPermission =
                    context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                if (notificationPermission != PackageManager.PERMISSION_GRANTED) {
                    Log.e(
                        TAG,
                        "POST_NOTIFICATIONS permission not granted, opening app notification settings"
                    )

                    try {
                        val settingsIntent =
                            Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                putExtra(
                                    android.provider.Settings.EXTRA_APP_PACKAGE,
                                    context.packageName
                                )
                            }
                        context.startActivity(settingsIntent)
                    } catch (settingsEx: Exception) {
                        Log.e(TAG, "Failed to open notification settings: ${settingsEx.message}")
                    }

                    manager.setStreamRequestId(null)
                    return ApiResponse.Error("stream_start_failed: Notification permission required. Please enable notifications and try again.")
                }
            }

            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val channelId = "stream_start_channel"
            val channel = NotificationChannel(
                channelId,
                "Start Streaming",
                NotificationManager.IMPORTANCE_HIGH
            )
            val notificationManager = context.getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)

            val notification = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("Start Screen Streaming")
                .setContentText("Tap to allow cloud screen sharing")
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()

            notificationManager.notify(3001, notification)

            return ApiResponse.Success("waiting_for_user_notification_tap")
        }
    }

    fun stopStream(graceful: Boolean = false): ApiResponse {
        if (graceful) {
            val manager = WebRtcManager.getInstance(context)
            manager.requestGracefulStop("cloud_stop")
            return ApiResponse.Success("Stop stream requested")
        }

        val intent = Intent(context, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_STOP_STREAM
        }
        // stopService instead of startService to avoid IllegalStateException on API 26+
        context.stopService(intent)
        return ApiResponse.Success("Stop stream requested")
    }

    fun handleWebRtcAnswer(sdp: String): ApiResponse {
        val manager = WebRtcManager.getInstance(context)
        if (!manager.isStreamActive())
            return ApiResponse.Error("No active stream")

        manager.handleAnswer(sdp)
        return ApiResponse.Success("SDP Answer processed")
    }

    fun handleWebRtcIce(candidateSdp: String, sdpMid: String, sdpMLineIndex: Int): ApiResponse {
        val manager = WebRtcManager.getInstance(context)
        if (!manager.isStreamActive())
            return ApiResponse.Error("No active stream")

        manager.handleIceCandidate(
            IceCandidate(sdpMid, sdpMLineIndex, candidateSdp)
        )
        return ApiResponse.Success("ICE Candidate processed")
    }

    fun handleWebRtcOffer(sdp: String, sessionId: String): ApiResponse {
        val manager = WebRtcManager.getInstance(context)
        if (!manager.isStreamActive())
            return ApiResponse.Error("No active stream - call stream/start first")

        manager.handleOffer(sdp, sessionId)
        return ApiResponse.Success("SDP Offer processed, answer will be sent")
    }

    private fun parseIceServers(json: JSONArray): List<PeerConnection.IceServer> {
        return (0 until json.length()).map { i ->
            val obj = json.getJSONObject(i)
            val urlsArray = obj.getJSONArray("urls")
            val urls = (0 until urlsArray.length()).map { urlsArray.getString(it) }
            if (urls.isEmpty()) {
                throw IllegalArgumentException("ICE server at index $i has empty urls array")
            }
            PeerConnection.IceServer.builder(urls)
                .setUsername(obj.optString("username", ""))
                .setPassword(obj.optString("credential", ""))
                .createIceServer()
        }
    }

    /**
     * Shows a dialog prompting the user to enable "Install unknown apps" permission.
     */
    private fun showInstallPermissionDialog() {
        try {
            val intent = PermissionDialogActivity.createInstallPermissionIntent(context)
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show install permission dialog", e)
        }
    }
}
