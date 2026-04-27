package com.mobilerun.portal.core

import android.graphics.Rect
import com.mobilerun.portal.service.MobilerunAccessibilityService
import com.mobilerun.portal.model.ElementNode
import com.mobilerun.portal.model.PhoneState
import org.json.JSONObject

class StateRepository(private val service: MobilerunAccessibilityService?) {

    fun getVisibleElements(): List<ElementNode> = service?.getVisibleElements() ?: emptyList()

    fun getFullTree(filter: Boolean): JSONObject? {
        val svc = service ?: return null
        val root = svc.rootInActiveWindow ?: pickFallbackRoot(svc) ?: return null
        val bounds = if (filter) svc.getScreenBounds() else null
        return AccessibilityTreeBuilder.buildFullAccessibilityTreeJson(root, bounds)
    }

    /**
     * Falls back to enumerating accessibility windows when `rootInActiveWindow`
     * returns null. Picks the topmost user-facing window with a non-null root.
     * Requires `flagRetrieveInteractiveWindows` in the service config.
     */
    private fun pickFallbackRoot(svc: MobilerunAccessibilityService): android.view.accessibility.AccessibilityNodeInfo? {
        val ws = svc.windows ?: return null
        return try {
            ws.sortedByDescending { it.layer }
                .firstOrNull {
                    it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION ||
                    it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM
                }
                ?.root
        } finally {
            ws.forEach { it.recycle() }
        }
    }

    fun getPhoneState(): PhoneState =
        service?.getPhoneState() ?: PhoneState(
            focusedElement = null,
            keyboardVisible = false,
            packageName = null,
            appName = null,
            isEditable = false,
            activityName = null,
        )

    fun getDeviceContext(): JSONObject = service?.getDeviceContext() ?: JSONObject()

    fun getScreenBounds(): Rect = service?.getScreenBounds() ?: Rect()

    fun setOverlayOffset(offset: Int): Boolean = service?.setOverlayOffset(offset) ?: false

    fun setOverlayVisible(visible: Boolean): Boolean = service?.setOverlayVisible(visible) ?: false

    fun isOverlayVisible(): Boolean = service?.isOverlayVisible() ?: false

    fun takeScreenshot(hideOverlay: Boolean): java.util.concurrent.CompletableFuture<String> {
        val liveService = service
        if (liveService != null) {
            return liveService.takeScreenshotBase64(hideOverlay)
        }
        return java.util.concurrent.CompletableFuture<String>().apply {
            completeExceptionally(IllegalStateException("Accessibility service not available"))
        }
    }

    fun updateSocketServerPort(port: Int): Boolean = service?.updateSocketServerPort(port) ?: false

    fun inputText(text: String, clear: Boolean): Boolean = service?.inputText(text, clear) ?: false
}
