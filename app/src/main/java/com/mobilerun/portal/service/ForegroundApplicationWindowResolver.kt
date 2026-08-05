package com.mobilerun.portal.service

import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.util.Collections
import java.util.IdentityHashMap

/** Resolves accessibility window changes to confirmed foreground application evidence. */
internal object ForegroundApplicationWindowResolver {
    private const val TAG = "ForegroundAppWindow"
    private const val UNDEFINED_WINDOW_ID = -1

    internal data class Candidate(
        val packageName: String,
        val windowId: Int,
    )

    private data class WindowDescriptor(
        val window: AccessibilityWindowInfo,
        val id: Int,
        val layer: Int,
        val type: Int,
        val isActive: Boolean,
        val isFocused: Boolean,
    )

    private data class RootPackage(val value: String?)

    fun resolve(
        event: AccessibilityEvent?,
        windowsProvider: () -> List<AccessibilityWindowInfo>?,
    ): Candidate? {
        if (event == null) return null

        val eventType = readEventType(event) ?: return null
        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) {
            return null
        }

        val eventWindowId = readEventWindowId(event)
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            (eventWindowId == null || eventWindowId == UNDEFINED_WINDOW_ID)
        ) {
            return null
        }

        val windows = readWindows(windowsProvider) ?: return null
        val uniqueWindows = uniqueByIdentity(windows)

        try {
            val descriptors = uniqueWindows.mapNotNull(::describeWindow)
            val selected = when (eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ->
                    selectStateChangedWindow(descriptors, eventWindowId!!)

                AccessibilityEvent.TYPE_WINDOWS_CHANGED -> selectWindowsChangedWindow(descriptors)
                else -> null
            } ?: return null

            val rootPackage = readRootPackage(selected) ?: return null
            val authoritativePackage = rootPackage.value
                ?.trim()
                ?.takeIf(String::isNotEmpty)
            if (authoritativePackage != null) {
                return Candidate(authoritativePackage, selected.id)
            }

            // Event metadata is only safe as a fallback when it describes the exact
            // qualified application window whose root was inspected above.
            if (eventWindowId == null ||
                eventWindowId == UNDEFINED_WINDOW_ID ||
                eventWindowId != selected.id
            ) {
                return null
            }
            val fallbackPackage = readEventPackage(event)
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?: return null
            return Candidate(fallbackPackage, selected.id)
        } finally {
            uniqueWindows.forEach(::recycleWindow)
        }
    }

    private fun selectStateChangedWindow(
        descriptors: List<WindowDescriptor>,
        eventWindowId: Int,
    ): WindowDescriptor? {
        return descriptors
            .asSequence()
            .filter {
                it.id != UNDEFINED_WINDOW_ID &&
                    it.id == eventWindowId &&
                    it.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                    (it.isFocused || it.isActive)
            }
            .sortedWith(
                compareByDescending<WindowDescriptor> { it.isFocused }
                    .thenByDescending { it.layer }
                    .thenBy { it.id },
            )
            .firstOrNull()
    }

    private fun selectWindowsChangedWindow(
        descriptors: List<WindowDescriptor>,
    ): WindowDescriptor? {
        val applications = descriptors.filter {
            it.id != UNDEFINED_WINDOW_ID &&
                it.type == AccessibilityWindowInfo.TYPE_APPLICATION
        }
        val focused = applications.filter { it.isFocused }
        val eligible = focused.ifEmpty { applications.filter { it.isActive } }
        return eligible.minWithOrNull(
            compareByDescending<WindowDescriptor> { it.layer }
                .thenBy { it.id },
        )
    }

    private fun readEventType(event: AccessibilityEvent): Int? = try {
        event.eventType
    } catch (error: RuntimeException) {
        logFailure("Unable to read accessibility event type", error)
        null
    }

    private fun readEventWindowId(event: AccessibilityEvent): Int? = try {
        event.windowId
    } catch (error: RuntimeException) {
        logFailure("Unable to read accessibility event window ID", error)
        null
    }

    private fun readEventPackage(event: AccessibilityEvent): String? = try {
        event.packageName?.toString()
    } catch (error: RuntimeException) {
        logFailure("Unable to read accessibility event package", error)
        null
    }

    private fun readWindows(
        windowsProvider: () -> List<AccessibilityWindowInfo>?,
    ): List<AccessibilityWindowInfo>? = try {
        windowsProvider()
    } catch (error: RuntimeException) {
        logFailure("Unable to read accessibility windows", error)
        null
    }

    private fun uniqueByIdentity(
        windows: List<AccessibilityWindowInfo>,
    ): List<AccessibilityWindowInfo> {
        val seen = Collections.newSetFromMap(
            IdentityHashMap<AccessibilityWindowInfo, Boolean>(),
        )
        return windows.filter(seen::add)
    }

    private fun describeWindow(window: AccessibilityWindowInfo): WindowDescriptor? = try {
        WindowDescriptor(
            window = window,
            id = window.id,
            layer = window.layer,
            type = window.type,
            isActive = window.isActive,
            isFocused = window.isFocused,
        )
    } catch (error: RuntimeException) {
        logFailure("Unable to inspect accessibility window", error)
        null
    }

    private fun readRootPackage(descriptor: WindowDescriptor): RootPackage? {
        val root = try {
            descriptor.window.root
        } catch (error: RuntimeException) {
            logFailure("Unable to read accessibility window root", error)
            return null
        } ?: return null

        return try {
            RootPackage(root.packageName?.toString())
        } catch (error: RuntimeException) {
            logFailure("Unable to read accessibility root package", error)
            null
        } finally {
            recycleRoot(root)
        }
    }

    private fun recycleWindow(window: AccessibilityWindowInfo) {
        try {
            window.recycle()
        } catch (error: RuntimeException) {
            logFailure("Unable to recycle accessibility window", error)
        }
    }

    private fun recycleRoot(root: AccessibilityNodeInfo) {
        try {
            root.recycle()
        } catch (error: RuntimeException) {
            logFailure("Unable to recycle accessibility root", error)
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
