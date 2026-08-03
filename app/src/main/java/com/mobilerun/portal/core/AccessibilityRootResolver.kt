package com.mobilerun.portal.core

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.mobilerun.portal.service.MobilerunAccessibilityService

internal object AccessibilityRootResolver {
    private const val TAG = "AccessibilityRootResolver"
    private const val UNDEFINED_WINDOW_ID = -1

    internal data class RootCandidate(
        val root: AccessibilityNodeInfo,
        val windowId: Int,
        val layer: Int,
    )

    private data class WindowDescriptor(
        val window: AccessibilityWindowInfo,
        val id: Int,
        val layer: Int,
        val type: Int,
    )

    fun resolve(service: MobilerunAccessibilityService): List<RootCandidate> {
        val activeRoot = readActiveRoot(service)
        val activeWindowId = activeRoot?.let(::readWindowId) ?: UNDEFINED_WINDOW_ID
        val windows = readWindows(service)

        if (windows == null) {
            return activeRoot?.let { listOf(RootCandidate(it, activeWindowId, 0)) }.orEmpty()
        }

        try {
            val descriptors = windows.mapNotNull(::describeWindow)
            val activeLayer = if (activeRoot != null) {
                descriptors
                    .singleOrNull {
                        activeWindowId != UNDEFINED_WINDOW_ID && it.id == activeWindowId
                    }
                    ?.layer
                    ?: return listOf(RootCandidate(activeRoot, activeWindowId, 0))
            } else {
                0
            }
            val candidates = mutableListOf<RootCandidate>()
            val knownWindowIds = mutableSetOf<Int>()

            if (activeRoot != null) {
                candidates += RootCandidate(activeRoot, activeWindowId, activeLayer)
                if (activeWindowId != UNDEFINED_WINDOW_ID) {
                    knownWindowIds += activeWindowId
                }
            }

            val eligibleWindows = descriptors
                .asSequence()
                .filter { descriptor ->
                    if (activeRoot != null) {
                        descriptor.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                            descriptor.layer > activeLayer
                    } else {
                        descriptor.type == AccessibilityWindowInfo.TYPE_APPLICATION ||
                            descriptor.type == AccessibilityWindowInfo.TYPE_SYSTEM
                    }
                }
                .sortedWith(
                    compareBy<WindowDescriptor> {
                        if (it.type == AccessibilityWindowInfo.TYPE_APPLICATION) 0 else 1
                    }
                        .thenByDescending { it.layer }
                        .thenBy { it.id },
                )

            eligibleWindows.forEach { descriptor ->
                if (descriptor.id != UNDEFINED_WINDOW_ID && descriptor.id in knownWindowIds) {
                    return@forEach
                }

                val root = readWindowRoot(descriptor) ?: return@forEach
                val windowId = descriptor.id.takeIf { it != UNDEFINED_WINDOW_ID }
                    ?: readWindowId(root)

                if (windowId != UNDEFINED_WINDOW_ID && windowId in knownWindowIds) {
                    recycleDuplicateRoot(root, candidates)
                    return@forEach
                }

                val duplicateRootIndex = candidates.indexOfFirst { candidate ->
                    (windowId == UNDEFINED_WINDOW_ID ||
                        candidate.windowId == UNDEFINED_WINDOW_ID) &&
                        candidate.root == root
                }
                if (duplicateRootIndex >= 0) {
                    val existing = candidates[duplicateRootIndex]
                    if (existing.root === activeRoot && existing.windowId == UNDEFINED_WINDOW_ID) {
                        candidates[duplicateRootIndex] = existing.copy(
                            windowId = windowId,
                            layer = descriptor.layer,
                        )
                        if (windowId != UNDEFINED_WINDOW_ID) {
                            knownWindowIds += windowId
                        }
                    }
                    recycleDuplicateRoot(root, candidates)
                    return@forEach
                }

                candidates += RootCandidate(root, windowId, descriptor.layer)
                if (windowId != UNDEFINED_WINDOW_ID) {
                    knownWindowIds += windowId
                }
            }

            return candidates
        } finally {
            windows.forEach(::recycleWindow)
        }
    }

    private fun readActiveRoot(
        service: MobilerunAccessibilityService,
    ): AccessibilityNodeInfo? = try {
        service.rootInActiveWindow
    } catch (e: RuntimeException) {
        logFailure("Unable to read active accessibility root", e)
        null
    }

    private fun readWindows(
        service: MobilerunAccessibilityService,
    ): List<AccessibilityWindowInfo>? = try {
        service.windows
    } catch (e: RuntimeException) {
        logFailure("Unable to read accessibility windows", e)
        null
    }

    private fun describeWindow(window: AccessibilityWindowInfo): WindowDescriptor? = try {
        WindowDescriptor(
            window = window,
            id = window.id,
            layer = window.layer,
            type = window.type,
        )
    } catch (e: RuntimeException) {
        logFailure("Unable to inspect accessibility window", e)
        null
    }

    private fun readWindowRoot(descriptor: WindowDescriptor): AccessibilityNodeInfo? = try {
        descriptor.window.root
    } catch (e: RuntimeException) {
        logFailure(
            "Unable to read accessibility window root id=${descriptor.id} layer=${descriptor.layer}",
            e,
        )
        null
    }

    private fun readWindowId(root: AccessibilityNodeInfo): Int = try {
        root.windowId
    } catch (e: RuntimeException) {
        logFailure("Unable to read accessibility root window ID", e)
        UNDEFINED_WINDOW_ID
    }

    private fun recycleDuplicateRoot(
        root: AccessibilityNodeInfo,
        candidates: List<RootCandidate>,
    ) {
        if (candidates.none { it.root === root }) {
            try {
                root.recycle()
            } catch (e: RuntimeException) {
                logFailure("Unable to recycle duplicate accessibility root", e)
            }
        }
    }

    private fun recycleWindow(window: AccessibilityWindowInfo) {
        try {
            window.recycle()
        } catch (e: RuntimeException) {
            logFailure("Unable to recycle accessibility window", e)
        }
    }

    private fun logFailure(message: String, error: RuntimeException) {
        try {
            Log.e(TAG, "$message: ${error.message}", error)
        } catch (_: RuntimeException) {
            // android.util.Log is unavailable in local JVM tests.
        }
    }
}
