package com.droidrun.portal.core

import android.graphics.Rect
import com.droidrun.portal.service.DroidrunAccessibilityService
import com.droidrun.portal.model.ElementNode
import com.droidrun.portal.model.PhoneState
import org.json.JSONObject

class StateRepository(private val service: DroidrunAccessibilityService) {

    fun getVisibleElements(): List<ElementNode> = service.getVisibleElements()

    fun getFullTree(filter: Boolean): JSONObject? {
        val root = service.rootInActiveWindow ?: return null
        val bounds = if (filter) service.getScreenBounds() else null
        return AccessibilityTreeBuilder.buildFullAccessibilityTreeJson(root, bounds)
    }

    fun getPhoneState(): PhoneState = service.getPhoneState()

    fun getDeviceContext(): JSONObject = service.getDeviceContext()

    fun getScreenBounds(): Rect = service.getScreenBounds()

    fun setOverlayOffset(offset: Int): Boolean = service.setOverlayOffset(offset)

    fun setOverlayVisible(visible: Boolean): Boolean = service.setOverlayVisible(visible)

    fun isOverlayVisible(): Boolean = service.isOverlayVisible()

    fun takeScreenshot(hideOverlay: Boolean): java.util.concurrent.CompletableFuture<String> =
        service.takeScreenshotBase64(hideOverlay)

    fun updateSocketServerPort(port: Int): Boolean = service.updateSocketServerPort(port)

    fun inputText(text: String, clear: Boolean): Boolean = service.inputText(text, clear)
}
