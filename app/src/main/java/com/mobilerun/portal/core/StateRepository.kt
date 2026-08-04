package com.mobilerun.portal.core

import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.mobilerun.portal.service.MobilerunAccessibilityService
import com.mobilerun.portal.model.ElementNode
import com.mobilerun.portal.model.PhoneState
import org.json.JSONArray
import org.json.JSONObject

class StateRepository(private val service: MobilerunAccessibilityService?) {
    companion object {
        private const val TAG = "StateRepository"
    }

    val hasAccessibilityService: Boolean
        get() = service != null

    fun getVisibleElements(): List<ElementNode> = service?.getVisibleElements() ?: emptyList()

    /**
     * True when an accessibility root window is currently resolvable (active
     * window or a user-facing fallback window). Lets callers tell a genuine
     * "no active window" freeze apart from a window that simply exposes no
     * semantic elements — e.g. a Flutter/game/WebView surface with no a11y
     * children — which must NOT be treated as an error.
     */
    fun hasActiveRoot(): Boolean {
        val svc = service ?: return false
        val candidates = AccessibilityRootResolver.resolve(svc)
        try {
            return candidates.isNotEmpty()
        } finally {
            recycleRoots(candidates.map { it.root })
        }
    }

    fun getFullTree(filter: Boolean): JSONObject? {
        val svc = service ?: return null
        val candidates = AccessibilityRootResolver.resolve(svc)
        var consumedCount = 0
        return try {
            val bounds = if (filter) svc.getScreenBounds() else null
            var primaryTree: JSONObject? = null

            for (candidate in candidates) {
                // AccessibilityTreeBuilder owns and recycles every root passed to it,
                // including when building fails. Mark the current candidate consumed
                // before invoking it so cleanup only recycles untouched roots.
                consumedCount++
                val tree = AccessibilityTreeBuilder.buildFullAccessibilityTreeJson(
                    candidate.root,
                    bounds,
                ) ?: continue

                val primary = primaryTree
                if (primary == null) {
                    primaryTree = tree
                } else {
                    appendAdditionalRoot(primary, tree)
                }
            }

            primaryTree
        } finally {
            recycleRoots(candidates.drop(consumedCount).map { it.root })
        }
    }

    private fun appendAdditionalRoot(primary: JSONObject, additionalRoot: JSONObject) {
        val children = primary.optJSONArray("children") ?: JSONArray().also {
            primary.put("children", it)
        }
        children.put(additionalRoot)
    }

    private fun recycleRoots(roots: Iterable<AccessibilityNodeInfo>) {
        roots.forEach { root ->
            try {
                root.recycle()
            } catch (e: RuntimeException) {
                try {
                    Log.e(TAG, "Unable to recycle accessibility root: ${e.message}", e)
                } catch (_: RuntimeException) {
                    // android.util.Log is unavailable in local JVM tests.
                }
            }
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
