package com.droidrun.portal.service

import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Utility to auto-accept the MediaProjection permission dialog.
 *
 * On newer Android versions, this can be a TWO-STEP dialog:
 * Step 1: Select "Entire screen" from Spinner (if present), then the button changes
 * Step 2: Click the positive button to confirm
 *
 * On other versions, there may be only a single "Start now" button.
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
    private const val PENDING_SPINNER_TIMEOUT_MS = 1500L
    private const val FAILURE_COOLDOWN_MS = 60_000L
    private const val ASSUMED_SELECTION_TTL_MS = 3000L
    private var pendingSpinnerWindowId: Int? = null
    private var pendingSpinnerRequestedAtMs = 0L
    private var blockedUntilMs = 0L
    private var assumedEntireScreenUntilMs = 0L

    // language  agnostic
    private const val DIALOG_VIEW_ID = "com.android.systemui:id/screen_share_permission_dialog"
    private const val SPINNER_VIEW_ID = "com.android.systemui:id/screen_share_mode_options"
    private const val POSITIVE_BUTTON_ID = "android:id/button1"  // "Next" or "Share screen"
    private const val LEGACY_ALERT_TITLE_ID = "android:id/alertTitle"
    private const val LEGACY_MESSAGE_ID = "android:id/message"
    private const val LEGACY_TEXT_MATCH = "recording or casting"
    private const val ANDROID_TEXT1_ID = "android:id/text1"

    // Fallback button texts for final confirmation
    private val START_BUTTON_TEXTS = listOf(
        "Share screen", "Start now", "Start", "Allow", "Accept", "OK", "Next"
    )

    // Fallback texts for "Entire screen" option
    private val ENTIRE_SCREEN_TEXTS = listOf(
        "Share entire screen", "Entire screen"
    )

    sealed class AutoAcceptResult {
        object NoAction : AutoAcceptResult()
        object ActionPerformed : AutoAcceptResult()
        data class Failed(val reason: String) : AutoAcceptResult()
    }

    /**
     * Check if this event is potentially from the MediaProjection dialog
     */
    fun isMediaProjectionDialog(
        event: AccessibilityEvent?,
        eventClassName: String? = null,
    ): Boolean {
        if (event == null) return false
        val className = eventClassName ?: event.className?.toString()
        if (!className.isNullOrEmpty() && className.contains(MEDIA_PROJECTION_ACTIVITY))
            return true

        val packageName = event.packageName?.toString() ?: return false
        return packageName == SYSTEM_UI_PACKAGE
    }

    /**
     * Attempt to auto-accept the MediaProjection dialog.
     * Returns the action status for caller-level handling.
     */
    fun tryAutoAccept(
        rootNode: AccessibilityNodeInfo?,
        eventClassName: String? = null,
    ): AutoAcceptResult {
        if (rootNode == null) return AutoAcceptResult.NoAction

        // Check cooldown
        val now = System.currentTimeMillis()
        if (now - lastSuccessTime < COOLDOWN_MS) {
            return AutoAcceptResult.NoAction
        }

        val windowId = rootNode.windowId
        clearExpiredBlock(now)
        if (isBlocked(now)) {
            return AutoAcceptResult.NoAction
        }

        val packageName = rootNode.packageName?.toString() ?: ""
        val isMediaProjectionActivity = !eventClassName.isNullOrEmpty() &&
                eventClassName.contains(MEDIA_PROJECTION_ACTIVITY)
        if (packageName != SYSTEM_UI_PACKAGE && !isMediaProjectionActivity) {
            clearTransientState()
            return AutoAcceptResult.NoAction
        }

        if (!isActualMediaProjectionDialog(rootNode, eventClassName)) {
            clearTransientState()
            return AutoAcceptResult.NoAction
        }

        Log.d(TAG, "Processing MediaProjection dialog...")

        clearPendingIfWindowChanged(windowId)

        val pendingResult = handlePendingSpinnerSelection(rootNode, windowId, now)
        if (pendingResult != null)
            return pendingResult

        if (isDropdownVisible(rootNode))
            return trySelectDropdownOption(rootNode, now)

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
                val assumeEntireScreen = isAssumedEntireScreen(now)
                spinner.recycle()

                if (!isEntireScreen && spinnerText.isNotEmpty() && !assumeEntireScreen) {
                    // Need to change spinner first, don't click button yet
                    positiveButton.recycle()
                    val opened = clickSpinnerToChange(rootNode)
                    if (opened) {
                        pendingSpinnerWindowId = windowId
                        pendingSpinnerRequestedAtMs = now
                        return AutoAcceptResult.ActionPerformed
                    }
                    return markFailure("Spinner present but could not open options")
                }
            }

            Log.i(TAG, "Clicking positive button")
            val result = positiveButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            positiveButton.recycle()
            if (result) {
                lastSuccessTime = now
                assumedEntireScreenUntilMs = 0L
                Log.i(TAG, "MediaProjection dialog accepted!")
            }
            return if (result) AutoAcceptResult.ActionPerformed else {
                markFailure("Failed to click positive button")
            }
        }

        return AutoAcceptResult.NoAction
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

    private fun handlePendingSpinnerSelection(
        rootNode: AccessibilityNodeInfo,
        windowId: Int,
        now: Long,
    ): AutoAcceptResult? {
        if (pendingSpinnerWindowId != windowId) return null

        if (isDropdownVisible(rootNode)) {
            pendingSpinnerWindowId = null
            return trySelectDropdownOption(rootNode, now)
        }

        if (now - pendingSpinnerRequestedAtMs >= PENDING_SPINNER_TIMEOUT_MS) {
            pendingSpinnerWindowId = null
            return markFailure("No selectable option after opening spinner")
        }

        return AutoAcceptResult.NoAction
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

    private fun clearPendingIfWindowChanged(windowId: Int) {
        if (pendingSpinnerWindowId != null && pendingSpinnerWindowId != windowId) {
            pendingSpinnerWindowId = null
            pendingSpinnerRequestedAtMs = 0L
        }
    }

    private fun clearTransientState() {
        pendingSpinnerWindowId = null
        pendingSpinnerRequestedAtMs = 0L
        assumedEntireScreenUntilMs = 0L
    }

    private fun clearExpiredBlock(now: Long) {
        if (blockedUntilMs == 0L) return
        if (now >= blockedUntilMs) blockedUntilMs = 0L
    }

    private fun markFailure(reason: String): AutoAcceptResult {
        blockedUntilMs = System.currentTimeMillis() + FAILURE_COOLDOWN_MS
        pendingSpinnerWindowId = null
        pendingSpinnerRequestedAtMs = 0L
        assumedEntireScreenUntilMs = 0L
        Log.w(TAG, "Auto-accept failed: $reason")
        return AutoAcceptResult.Failed(reason)
    }

    private fun isBlocked(now: Long): Boolean {
        return blockedUntilMs != 0L && now < blockedUntilMs
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
                if (isInSpinner(match))
                    continue

                // Check if this node or its parent is clickable
                if (match.isClickable || match.isCheckable) {
                    matches.filter { it != match }.forEach { it.recycle() }
                    return match
                }
                val clickableParent = findClickableParent(match)
                if (clickableParent != null && !isInSpinner(clickableParent)) {
                    matches.forEach { it.recycle() }
                    return clickableParent
                }
            }
            matches.forEach { it.recycle() }
        }
        return null
    }

    private data class DropdownOptionResult(
        val option: AccessibilityNodeInfo?,
        val hasDropdown: Boolean,
    )

    private fun findSecondOptionByPosition(root: AccessibilityNodeInfo): DropdownOptionResult {
        val listNode = findFirstListNode(root)
        if (listNode != null) {
            val second = if (listNode.childCount >= 2) listNode.getChild(1) else null
            listNode.recycle()
            if (second != null) {
                if (second.isClickable)
                    return DropdownOptionResult(second, true)

                val clickableParent = findClickableParent(second)
                second.recycle()
                if (clickableParent != null) {
                    return DropdownOptionResult(clickableParent, true)
                }
            }
            return DropdownOptionResult(null, true)
        }

        val textNodes = root.findAccessibilityNodeInfosByViewId(ANDROID_TEXT1_ID)
        if (textNodes.isNotEmpty()) {
            val inListNodes = textNodes.filter { isInList(it) }
            if (inListNodes.size >= 2) {
                val second = inListNodes[1]
                val option = if (second.isClickable || second.isCheckable) {
                    second
                } else {
                    findClickableParent(second)
                }
                textNodes.forEach { node ->
                    if (node != second && node != option) {
                        node.recycle()
                    }
                }
                if (option != second) {
                    second.recycle()
                }
                return DropdownOptionResult(option, true)
            }
            textNodes.forEach { it.recycle() }
            return DropdownOptionResult(null, inListNodes.isNotEmpty())
        }

        return DropdownOptionResult(null, false)
    }

    private fun findFirstListNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val className = node.className?.toString().orEmpty()
        if (className.contains("ListView") || className.contains("RecyclerView")) {
            return AccessibilityNodeInfo.obtain(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findFirstListNode(child)
            child.recycle()
            if (result != null) return result
        }
        return null
    }

    private fun isDropdownVisible(root: AccessibilityNodeInfo): Boolean {
        val listNode = findFirstListNode(root)
        if (listNode != null) {
            listNode.recycle()
            return true
        }
        val textNodes = root.findAccessibilityNodeInfosByViewId(ANDROID_TEXT1_ID)
        val listTextCount = textNodes.count { isInList(it) }
        val hasDropdown = listTextCount >= 2
        textNodes.forEach { it.recycle() }
        return hasDropdown
    }

    private fun trySelectDropdownOption(
        rootNode: AccessibilityNodeInfo,
        now: Long,
    ): AutoAcceptResult {
        val entireScreenOption = findEntireScreenOption(rootNode)
        if (entireScreenOption != null) {
            Log.d(TAG, "Selecting 'Entire screen' option")
            val result = performOptionAction(entireScreenOption)
            entireScreenOption.recycle()
            if (result) {
                assumeEntireScreenSelected(now)
                return AutoAcceptResult.ActionPerformed
            }
            Log.d(TAG, "Failed to click entire screen option, trying fallback")
        }

        val dropdownOption = findSecondOptionByPosition(rootNode)
        if (dropdownOption.option != null) {
            Log.d(TAG, "Selecting second option (fallback)")
            val result = performOptionAction(dropdownOption.option)
            dropdownOption.option.recycle()
            return if (result) {
                assumeEntireScreenSelected(now)
                AutoAcceptResult.ActionPerformed
            } else {
                markFailure("Failed to select second option")
            }
        }

        return if (dropdownOption.hasDropdown) {
            markFailure("Dropdown options visible but no selectable option found")
        } else {
            AutoAcceptResult.NoAction
        }
    }

    private fun assumeEntireScreenSelected(now: Long) {
        assumedEntireScreenUntilMs = now + ASSUMED_SELECTION_TTL_MS
    }

    private fun isAssumedEntireScreen(now: Long): Boolean {
        if (assumedEntireScreenUntilMs == 0L) return false
        if (now <= assumedEntireScreenUntilMs) return true
        assumedEntireScreenUntilMs = 0L
        return false
    }

    private fun performOptionAction(node: AccessibilityNodeInfo): Boolean {
        if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true
        }
        if (node.isCheckable || hasAction(node, AccessibilityNodeInfo.ACTION_SELECT)) {
            if (node.performAction(AccessibilityNodeInfo.ACTION_SELECT)) {
                return true
            }
        }
        val child = findClickableOrCheckableChild(node, 2)
        if (child != null) {
            val result = child.performAction(AccessibilityNodeInfo.ACTION_CLICK) ||
                    child.performAction(AccessibilityNodeInfo.ACTION_SELECT)
            child.recycle()
            if (result) return true
        }
        val parent = findClickableParent(node)
        if (parent != null) {
            val result = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK) ||
                    parent.performAction(AccessibilityNodeInfo.ACTION_SELECT)
            parent.recycle()
            if (result) return true
        }
        return false
    }

    private fun hasAction(node: AccessibilityNodeInfo, actionId: Int): Boolean {
        return node.actionList.any { it.id == actionId }
    }

    private fun findClickableOrCheckableChild(
        node: AccessibilityNodeInfo,
        maxDepth: Int,
    ): AccessibilityNodeInfo? {
        if (maxDepth <= 0) return null
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (child.isClickable || child.isCheckable) {
                return child
            }
            val result = findClickableOrCheckableChild(child, maxDepth - 1)
            child.recycle()
            if (result != null) return result
        }
        return null
    }

    private fun isInSpinner(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(node)
        var depth = 0
        while (current != null && depth < 6) {
            val className = current.className?.toString().orEmpty()
            val parent = current.parent
            current.recycle()
            if (className.contains("Spinner")) {
                parent?.recycle()
                return true
            }
            current = parent
            depth++
        }
        return false
    }

    private fun isInList(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(node)
        var depth = 0
        while (current != null && depth < 6) {
            val className = current.className?.toString().orEmpty()
            val parent = current.parent
            current.recycle()
            if (className.contains("ListView") || className.contains("RecyclerView")) {
                parent?.recycle()
                return true
            }
            current = parent
            depth++
        }
        return false
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
