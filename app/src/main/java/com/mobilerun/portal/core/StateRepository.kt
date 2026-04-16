package com.mobilerun.portal.core

import android.graphics.Rect
import com.mobilerun.portal.service.MobilerunAccessibilityService
import com.mobilerun.portal.model.ElementNode
import com.mobilerun.portal.model.PhoneState
import org.json.JSONObject

class StateRepository(private val service: MobilerunAccessibilityService?) {

    fun getVisibleElements(): List<ElementNode> = service?.getVisibleElements() ?: emptyList()

    fun getFullTree(filter: Boolean): JSONObject? {
        val root = service?.rootInActiveWindow ?: return null
        val bounds = if (filter) service.getScreenBounds() else null
        return AccessibilityTreeBuilder.buildFullAccessibilityTreeJson(root, bounds)
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
