package com.droidrun.portal.service

import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Utility to auto-accept the MediaProjection permission dialog.
 *
 * On Android 14+, this is a TWO-STEP dialog:
 * Step 1: Select "Entire screen" from Spinner, then the button changes
 * Step 2: Click the positive button to confirm
 *
 * Uses view IDs for language-agnostic detection where possible.
 */
object MediaProjectionAutoAccept {
    private const val TAG = "MediaProjectionAutoAccept"

    private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
    private const val MEDIA_PROJECTION_ACTIVITY = "MediaProjectionPermissionActivity"

    // Cooldown to prevent repeated processing after success
    private const val COOLDOWN_MS = 2000L
    private var lastSuccessTime = 0L

    // language  agnostic
    private const val DIALOG_VIEW_ID = "com.android.systemui:id/screen_share_permission_dialog"
    private const val SPINNER_VIEW_ID = "com.android.systemui:id/screen_share_mode_options"
    private const val POSITIVE_BUTTON_ID = "android:id/button1"  // "Next" or "Share screen"
    private const val LEGACY_ALERT_TITLE_ID = "android:id/alertTitle"
    private const val LEGACY_MESSAGE_ID = "android:id/message"
    private const val LEGACY_TEXT_MATCH = "recording or casting"

    // Fallback button texts for final confirmation
    private val START_BUTTON_TEXTS = listOf(
        "Share screen", "Start now", "Start", "Allow", "Accept", "OK", "Next"
    )

    // Fallback texts for "Entire screen" option
    private val ENTIRE_SCREEN_TEXTS = listOf(
        "Share entire screen", "Entire screen"
    )

    /**
     * Check if this event is potentially from the MediaProjection dialog
     */
    fun isMediaProjectionDialog(event: AccessibilityEvent?): Boolean {
        if (event == null) return false
        val packageName = event.packageName?.toString() ?: return false
        return packageName == SYSTEM_UI_PACKAGE
    }

    /**
     * Attempt to auto-accept the MediaProjection dialog.
     * Returns true if an action was performed.
     */
    fun tryAutoAccept(rootNode: AccessibilityNodeInfo?, eventClassName: String? = null): Boolean {
        if (rootNode == null) return false

        // Check cooldown
        val now = System.currentTimeMillis()
        if (now - lastSuccessTime < COOLDOWN_MS) {
            return false
        }

        val packageName = rootNode.packageName?.toString() ?: ""
        if (packageName != SYSTEM_UI_PACKAGE)
            return false

        if (!isActualMediaProjectionDialog(rootNode, eventClassName))
            return false

        Log.d(TAG, "Processing MediaProjection dialog...")

        val positiveButton = findNodeByViewId(rootNode, POSITIVE_BUTTON_ID)
            ?: findButtonByTexts(rootNode, START_BUTTON_TEXTS)

        if (positiveButton != null) {
            // Check if need to change spinner first (if spinner shows wrong option)
            val spinner = findNodeByViewId(rootNode, SPINNER_VIEW_ID) ?: findSpinner(rootNode)
            if (spinner != null) {
                val spinnerText = getSpinnerSelectedText(spinner)
                val isEntireScreen = ENTIRE_SCREEN_TEXTS.any {
                    spinnerText.contains(it, ignoreCase = true)
                }
                spinner.recycle()

                if (!isEntireScreen && spinnerText.isNotEmpty()) {
                    // Need to change spinner first, don't click button yet
                    positiveButton.recycle()
                    return clickSpinnerToChange(rootNode)
                }
            }

            Log.i(TAG, "Clicking positive button")
            val result = positiveButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            positiveButton.recycle()
            if (result) {
                lastSuccessTime = now
                Log.i(TAG, "MediaProjection dialog accepted!")
            }
            return result
        }

        // Try to select "Entire screen" in dropdown
        val entireScreenOption = findEntireScreenOption(rootNode)
        if (entireScreenOption != null) {
            Log.d(TAG, "Selecting 'Entire screen' option")
            val result = entireScreenOption.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            entireScreenOption.recycle()
            return result
        }

        return false
    }

    private fun clickSpinnerToChange(rootNode: AccessibilityNodeInfo): Boolean {
        val spinner = findNodeByViewId(rootNode, SPINNER_VIEW_ID) ?: findSpinner(rootNode)
        if (spinner != null) {
            Log.d(TAG, "Clicking Spinner to select 'Entire screen'")
            val result = spinner.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            spinner.recycle()
            return result
        }
        return false
    }

    private fun isActualMediaProjectionDialog(
        node: AccessibilityNodeInfo,
        eventClassName: String?,
    ): Boolean {
        if (!eventClassName.isNullOrEmpty() &&
            eventClassName.contains(MEDIA_PROJECTION_ACTIVITY)
        ) {
            return true
        }

        val dialogNodes = node.findAccessibilityNodeInfosByViewId(DIALOG_VIEW_ID)
        if (dialogNodes.isNotEmpty()) {
            dialogNodes.forEach { it.recycle() }
            return true
        }

        val spinnerNodes = node.findAccessibilityNodeInfosByViewId(SPINNER_VIEW_ID)
        if (spinnerNodes.isNotEmpty()) {
            spinnerNodes.forEach { it.recycle() }
            return true
        }

        // fallback
        for (text in ENTIRE_SCREEN_TEXTS) {
            val matches = node.findAccessibilityNodeInfosByText(text)
            if (matches.isNotEmpty()) {
                matches.forEach { it.recycle() }
                return true
            }
        }

        val legacyTitle = findNodeByViewId(node, LEGACY_ALERT_TITLE_ID)
        if (legacyTitle != null) {
            val titleText = legacyTitle.text?.toString().orEmpty()
            legacyTitle.recycle()
            if (titleText.contains(LEGACY_TEXT_MATCH, ignoreCase = true)) {
                return true
            }
        }

        val legacyMessage = findNodeByViewId(node, LEGACY_MESSAGE_ID)
        if (legacyMessage != null) {
            val messageText = legacyMessage.text?.toString().orEmpty()
            legacyMessage.recycle()
            if (messageText.contains(LEGACY_TEXT_MATCH, ignoreCase = true)) {
                return true
            }
        }

        return false
    }

    private fun findNodeByViewId(
        root: AccessibilityNodeInfo,
        viewId: String,
    ): AccessibilityNodeInfo? {
        val nodes = root.findAccessibilityNodeInfosByViewId(viewId)
        if (nodes.isNotEmpty()) {
            val node = nodes.first()
            nodes.drop(1).forEach { it.recycle() }
            return node
        }
        return null
    }

    private fun findEntireScreenOption(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (text in ENTIRE_SCREEN_TEXTS) {
            val matches = root.findAccessibilityNodeInfosByText(text)
            for (match in matches) {
                // Check if this node or its parent is clickable
                if (match.isClickable) {
                    matches.filter { it != match }.forEach { it.recycle() }
                    return match
                }
                val clickableParent = findClickableParent(match)
                if (clickableParent != null) {
                    matches.forEach { it.recycle() }
                    return clickableParent
                }
            }
            matches.forEach { it.recycle() }
        }
        return null
    }

    private fun findButtonByTexts(
        node: AccessibilityNodeInfo,
        texts: List<String>,
    ): AccessibilityNodeInfo? {
        for (text in texts) {
            val matches = node.findAccessibilityNodeInfosByText(text)
            for (match in matches) {
                val matchText = match.text?.toString().orEmpty()
                val isButton = match.className?.toString()?.contains("Button") == true
                val isClickable = match.isClickable

                if ((matchText.equals(text, ignoreCase = true) || matchText.contains(
                        text,
                        ignoreCase = true
                    ))
                    && (isButton || isClickable)
                ) {
                    matches.filter { it != match }.forEach { it.recycle() }
                    return match
                }
            }
            matches.forEach { it.recycle() }
        }
        return null
    }

    private fun findClickableParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var parent = node.parent ?: return null
        var depth = 0

        while (depth < 5) {
            if (parent.isClickable) return parent
            val nextParent = parent.parent
            parent.recycle()
            parent = nextParent ?: return null
            depth++
        }
        parent.recycle()
        return null
    }

    private fun findSpinner(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.className?.toString()?.contains("Spinner") == true) {
            return AccessibilityNodeInfo.obtain(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findSpinner(child)
            child.recycle()
            if (result != null) return result
        }
        return null
    }

    private fun getSpinnerSelectedText(spinner: AccessibilityNodeInfo): String {
        spinner.text?.toString()?.let { if (it.isNotEmpty()) return it }
        for (i in 0 until spinner.childCount) {
            val child = spinner.getChild(i) ?: continue
            val text = child.text?.toString() ?: ""
            child.recycle()
            if (text.isNotEmpty()) return text
        }
        return ""
    }
}
