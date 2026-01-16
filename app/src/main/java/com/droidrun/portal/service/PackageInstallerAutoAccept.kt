package com.droidrun.portal.service

import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque

/**
 * Utility to auto-accept app install confirmation dialogs.
 *
 * Uses known package installer packages, class name hints, and common button
 * IDs/texts to find the positive action and click it.
 */
object PackageInstallerAutoAccept {
    private const val TAG = "PackageInstallerAutoAccept"
    private const val COOLDOWN_MS = 2000L
    private const val FAILURE_LOG_COOLDOWN_MS = 10_000L
    private const val MAX_DUMP_NODES = 80
    private const val MAX_SEARCH_NODES = 120

    private var lastSuccessAtMs = 0L
    private var lastFailureLogAtMs = 0L

    private val KNOWN_PACKAGES = setOf(
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.miui.packageinstaller",
        "com.samsung.android.packageinstaller",
    )

    private val CLASS_HINTS = listOf(
        "PackageInstallerActivity",
        "InstallAppProgress",
        "InstallAppConfirmation",
        "InstallAppRequest",
    )

    private val POSITIVE_BUTTON_IDS = listOf(
        "com.android.packageinstaller:id/install_button",
        "com.android.packageinstaller:id/confirm_button",
        "com.android.packageinstaller:id/ok_button",
        "com.google.android.packageinstaller:id/ok_button",
        "com.google.android.packageinstaller:id/confirm_button",
        "com.google.android.packageinstaller:id/install_button",
        "com.miui.packageinstaller:id/ok_button",
        "com.samsung.android.packageinstaller:id/ok_button",
        "android:id/button1",
    )

    private val POSITIVE_BUTTON_TEXTS = listOf(
        "Install",
        "Install anyway",
        "Update",
    )

    private val INSTALL_CONFIRM_VIEW_IDS = KNOWN_PACKAGES.flatMap { pkg ->
        listOf(
            "$pkg:id/install_confirm_question",
            "$pkg:id/install_confirm_question_update",
        )
    }

    private val INSTALL_CONFIRM_ID_SUFFIXES = listOf(
        "/install_confirm_question",
        "/install_confirm_question_update",
    )

    private val INSTALL_BUTTON_ID_SUFFIXES = listOf(
        "/install_button",
    )

    sealed class AutoAcceptResult {
        object NoAction : AutoAcceptResult()
        object ActionPerformed : AutoAcceptResult()
        data class Failed(val reason: String) : AutoAcceptResult()
    }

    fun isInstallDialog(
        event: AccessibilityEvent?,
        eventClassName: String? = null,
    ): Boolean {
        if (event == null) return false
        val className = eventClassName ?: event.className?.toString().orEmpty()
        if (className.isNotEmpty() && CLASS_HINTS.any { className.contains(it) }) {
            return true
        }
        val packageName = event.packageName?.toString().orEmpty()
        return KNOWN_PACKAGES.contains(packageName)
    }

    fun tryAutoAccept(
        rootNode: AccessibilityNodeInfo?,
        eventClassName: String? = null,
    ): AutoAcceptResult {
        if (rootNode == null) return AutoAcceptResult.NoAction

        val now = System.currentTimeMillis()
        if (now - lastSuccessAtMs < COOLDOWN_MS) {
            return AutoAcceptResult.NoAction
        }

        val packageName = rootNode.packageName?.toString().orEmpty()
        val className = eventClassName.orEmpty()
        if (!isLikelyInstaller(packageName, className)) {
            return AutoAcceptResult.NoAction
        }

        if (AutoAcceptGate.shouldDumpInstallTree()) {
            logTreeDump(rootNode, "install dialog snapshot")
        }

        if (!hasInstallPromptSignal(rootNode)) {
            return AutoAcceptResult.NoAction
        }

        val positiveButton = findPositiveButton(rootNode)
        if (positiveButton == null) {
            logTreeDumpIfNeeded(rootNode, "positive button not found", now)
            return AutoAcceptResult.Failed("positive button not found")
        }

        val clicked = positiveButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        positiveButton.recycle()

        return if (clicked) {
            lastSuccessAtMs = now
            Log.i(TAG, "Install dialog accepted")
            AutoAcceptResult.ActionPerformed
        } else {
            logTreeDumpIfNeeded(rootNode, "positive button click failed", now)
            AutoAcceptResult.Failed("positive button click failed")
        }
    }

    private fun isLikelyInstaller(packageName: String, className: String): Boolean {
        if (className.isNotEmpty() && CLASS_HINTS.any { className.contains(it) }) {
            return true
        }
        return KNOWN_PACKAGES.contains(packageName)
    }

    private fun findPositiveButton(rootNode: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (viewId in POSITIVE_BUTTON_IDS) {
            val node = findNodeByViewId(rootNode, viewId)
            if (node != null) return node
        }
        return findButtonByText(rootNode)
    }

    private fun findNodeByViewId(
        rootNode: AccessibilityNodeInfo,
        viewId: String,
    ): AccessibilityNodeInfo? {
        val nodes = rootNode.findAccessibilityNodeInfosByViewId(viewId)
        if (nodes.isEmpty()) return null
        var candidate: AccessibilityNodeInfo? = null
        for (node in nodes) {
            val clickable = findClickableParent(node)
            if (clickable != null) {
                candidate = clickable
                break
            }
        }
        for (node in nodes) {
            if (node != candidate) {
                node.recycle()
            }
        }
        return candidate
    }

    private fun findButtonByText(rootNode: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (text in POSITIVE_BUTTON_TEXTS) {
            val nodes = rootNode.findAccessibilityNodeInfosByText(text)
            if (nodes.isEmpty()) continue
            var candidate: AccessibilityNodeInfo? = null
            for (node in nodes) {
                val clickable = findClickableParent(node)
                if (clickable != null) {
                    candidate = clickable
                    break
                }
            }
            for (node in nodes) {
                if (node != candidate) {
                    node.recycle()
                }
            }
            if (candidate != null) return candidate
        }
        return null
    }

    private fun hasInstallPromptSignal(rootNode: AccessibilityNodeInfo): Boolean {
        if (hasAnyViewId(rootNode, INSTALL_CONFIRM_VIEW_IDS)) return true
        if (hasViewIdSuffix(rootNode, INSTALL_BUTTON_ID_SUFFIXES)) return true
        return hasViewIdSuffix(rootNode, INSTALL_CONFIRM_ID_SUFFIXES)
    }

    private fun hasAnyViewId(
        rootNode: AccessibilityNodeInfo,
        viewIds: List<String>,
    ): Boolean {
        for (viewId in viewIds) {
            val nodes = rootNode.findAccessibilityNodeInfosByViewId(viewId)
            if (nodes.isNotEmpty()) {
                nodes.forEach { it.recycle() }
                return true
            }
        }
        return false
    }

    private fun hasViewIdSuffix(
        rootNode: AccessibilityNodeInfo,
        suffixes: List<String>,
    ): Boolean {
        val queue: ArrayDeque<AccessibilityNodeInfo> = ArrayDeque()
        queue.add(rootNode)
        var count = 0
        while (queue.isNotEmpty() && count < MAX_SEARCH_NODES) {
            val node = queue.removeFirst()
            val viewId = node.viewIdResourceName.orEmpty()
            if (suffixes.any { viewId.endsWith(it) }) {
                recycleQueue(queue, rootNode)
                if (node != rootNode) node.recycle()
                return true
            }
            val childCount = node.childCount
            for (i in 0 until childCount) {
                val child = node.getChild(i)
                if (child != null) queue.add(child)
            }
            if (node != rootNode) {
                node.recycle()
            }
            count += 1
        }
        recycleQueue(queue, rootNode)
        return false
    }

    private fun recycleQueue(
        queue: ArrayDeque<AccessibilityNodeInfo>,
        rootNode: AccessibilityNodeInfo,
    ) {
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node != rootNode) {
                node.recycle()
            }
        }
    }

    private fun findClickableParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isClickable) return node
        var parent = node.parent
        while (parent != null) {
            if (parent.isClickable) return parent
            val next = parent.parent
            parent.recycle()
            parent = next
        }
        return null
    }

    private fun logTreeDumpIfNeeded(
        rootNode: AccessibilityNodeInfo,
        reason: String,
        now: Long,
    ) {
        if (now - lastFailureLogAtMs < FAILURE_LOG_COOLDOWN_MS) return
        lastFailureLogAtMs = now
        Log.w(TAG, "Auto-accept failed: $reason. Dumping accessibility tree...")
        logTreeDump(rootNode)
    }

    private fun logTreeDump(
        rootNode: AccessibilityNodeInfo,
        reason: String? = null,
    ) {
        if (reason != null) {
            Log.i(TAG, "Dumping accessibility tree: $reason")
        }

        val queue: ArrayDeque<Pair<AccessibilityNodeInfo, Int>> = ArrayDeque()
        queue.add(rootNode to 0)
        var count = 0
        while (queue.isNotEmpty() && count < MAX_DUMP_NODES) {
            val (node, depth) = queue.removeFirst()
            val indent = "  ".repeat(depth.coerceAtMost(6))
            Log.w(TAG, indent + describeNode(node))
            val childCount = node.childCount
            for (i in 0 until childCount) {
                val child = node.getChild(i)
                if (child != null) queue.add(child to depth + 1)
            }
            if (node != rootNode) {
                node.recycle()
            }
            count += 1
        }

        while (queue.isNotEmpty()) {
            val (node, _) = queue.removeFirst()
            if (node != rootNode) {
                node.recycle()
            }
        }
    }

    private fun describeNode(node: AccessibilityNodeInfo): String {
        val className = node.className?.toString().orEmpty()
        val viewId = node.viewIdResourceName.orEmpty()
        val text = node.text?.toString()?.take(40).orEmpty()
        val contentDesc = node.contentDescription?.toString()?.take(40).orEmpty()
        return "class=$className id=$viewId text=$text desc=$contentDesc clickable=${node.isClickable} enabled=${node.isEnabled}"
    }
}
