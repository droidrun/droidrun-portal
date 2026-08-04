package com.mobilerun.portal.core

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@Suppress("DEPRECATION")
class PopupWindowFixtureInstrumentedTest {

    @Test
    fun nonFocusablePopup_isASeparateInteractiveApplicationWindow() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val uiAutomation = instrumentation.uiAutomation

        withInteractiveWindowRetrieval(uiAutomation) {
            ActivityScenario.launch(PopupWindowFixtureActivity::class.java).use { scenario ->
                waitForSnapshot(uiAutomation, "fixture activity to become active") { snapshot ->
                    snapshot.activeRoot.containsUnderlyingMarker
                }

                scenario.onActivity { it.showPopup(focusable = false) }

                val shown = waitForSnapshot(
                    uiAutomation,
                    "non-focusable popup to appear as a separate interactive window",
                ) { snapshot ->
                    val popupWindows = snapshot.windows.filter { it.containsPopupMarker }
                    snapshot.activeRoot.containsUnderlyingMarker &&
                        !snapshot.activeRoot.containsPopupMarker &&
                        popupWindows.size == 1 &&
                        popupWindows.single().containsPopupActionMarker &&
                        popupWindows.single().id != snapshot.activeRoot.windowId
                }

                val popupWindow = shown.windows.single { it.containsPopupMarker }
                assertEquals(AccessibilityWindowInfo.TYPE_APPLICATION, popupWindow.type)
                assertNotEquals(shown.activeRoot.windowId, popupWindow.id)
                assertFalse(shown.activeRoot.containsPopupMarker)
                assertTrue(performPopupAction(uiAutomation))

                waitForSnapshot(uiAutomation, "popup action click to reach the activity") { snapshot ->
                    snapshot.activeRoot.containsActionClickedMarker &&
                        snapshot.windows.any { it.containsPopupMarker }
                }

                scenario.onActivity { it.dismissPopup() }

                waitForSnapshot(uiAutomation, "non-focusable popup to be dismissed") { snapshot ->
                    snapshot.activeRoot.containsUnderlyingMarker &&
                        snapshot.windows.none {
                            it.containsPopupMarker || it.containsPopupActionMarker
                        }
                }
            }
        }
    }

    @Test
    fun focusablePopup_becomesTheActiveApplicationWindow() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val uiAutomation = instrumentation.uiAutomation

        withInteractiveWindowRetrieval(uiAutomation) {
            ActivityScenario.launch(PopupWindowFixtureActivity::class.java).use { scenario ->
                waitForSnapshot(uiAutomation, "fixture activity to become active") { snapshot ->
                    snapshot.activeRoot.containsUnderlyingMarker
                }

                scenario.onActivity { it.showPopup(focusable = true) }

                val shown = waitForSnapshot(
                    uiAutomation,
                    "focusable popup to become the active window",
                ) { snapshot ->
                    val popupWindows = snapshot.windows.filter { it.containsPopupMarker }
                    snapshot.activeRoot.containsPopupMarker &&
                        snapshot.activeRoot.containsPopupActionMarker &&
                        popupWindows.size == 1 &&
                        popupWindows.single().id == snapshot.activeRoot.windowId &&
                        popupWindows.single().isActive &&
                        popupWindows.single().isFocused
                }

                val popupWindow = shown.windows.single { it.containsPopupMarker }
                assertEquals(AccessibilityWindowInfo.TYPE_APPLICATION, popupWindow.type)
                assertEquals(shown.activeRoot.windowId, popupWindow.id)
                assertFalse(shown.activeRoot.containsUnderlyingMarker)

                scenario.onActivity { it.dismissPopup() }

                waitForSnapshot(uiAutomation, "activity to become active after popup dismissal") {
                    snapshot ->
                    snapshot.activeRoot.containsUnderlyingMarker &&
                        !snapshot.activeRoot.containsPopupMarker &&
                        snapshot.windows.none { it.containsPopupMarker }
                }
            }
        }
    }

    private fun withInteractiveWindowRetrieval(
        uiAutomation: UiAutomation,
        block: () -> Unit,
    ) {
        val serviceInfo = uiAutomation.serviceInfo
        val originalFlags = serviceInfo.flags

        try {
            serviceInfo.flags = originalFlags or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            uiAutomation.serviceInfo = serviceInfo
            block()
        } finally {
            serviceInfo.flags = originalFlags
            uiAutomation.serviceInfo = serviceInfo
        }
    }

    private fun waitForSnapshot(
        uiAutomation: UiAutomation,
        description: String,
        predicate: (UiSnapshot) -> Boolean,
    ): UiSnapshot {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = SystemClock.uptimeMillis() + WINDOW_TIMEOUT_MS
        var lastSnapshot: UiSnapshot

        do {
            instrumentation.waitForIdleSync()
            lastSnapshot = takeSnapshot(uiAutomation)
            if (predicate(lastSnapshot)) {
                return lastSnapshot
            }
            SystemClock.sleep(WINDOW_POLL_INTERVAL_MS)
        } while (SystemClock.uptimeMillis() < deadline)

        throw AssertionError("Timed out waiting for $description; last snapshot=$lastSnapshot")
    }

    private fun takeSnapshot(uiAutomation: UiAutomation): UiSnapshot {
        val activeRoot = uiAutomation.rootInActiveWindow
        val activeRootSnapshot = try {
            activeRoot?.toRootSnapshot() ?: RootSnapshot.EMPTY
        } finally {
            activeRoot?.recycle()
        }

        val windows = uiAutomation.windows.orEmpty()
        val windowSnapshots = try {
            windows.map { window -> window.toWindowSnapshot() }
        } finally {
            windows.forEach { it.recycle() }
        }

        return UiSnapshot(activeRootSnapshot, windowSnapshots)
    }

    private fun AccessibilityWindowInfo.toWindowSnapshot(): WindowSnapshot {
        val windowRoot = root
        val rootSnapshot = try {
            windowRoot?.toRootSnapshot() ?: RootSnapshot.EMPTY
        } finally {
            windowRoot?.recycle()
        }

        return WindowSnapshot(
            id = id,
            type = type,
            layer = layer,
            isActive = isActive,
            isFocused = isFocused,
            containsUnderlyingMarker = rootSnapshot.containsUnderlyingMarker,
            containsPopupMarker = rootSnapshot.containsPopupMarker,
            containsPopupActionMarker = rootSnapshot.containsPopupActionMarker,
        )
    }

    private fun AccessibilityNodeInfo.toRootSnapshot(): RootSnapshot = RootSnapshot(
        windowId = windowId,
        containsUnderlyingMarker = containsExactMarker(
            PopupWindowFixtureActivity.UNDERLYING_MARKER,
        ),
        containsPopupMarker = containsExactMarker(PopupWindowFixtureActivity.POPUP_MARKER),
        containsPopupActionMarker = containsExactMarker(
            PopupWindowFixtureActivity.POPUP_ACTION_MARKER,
        ),
        containsActionClickedMarker = containsExactMarker(
            PopupWindowFixtureActivity.ACTION_CLICKED_MARKER,
        ),
    )

    private fun AccessibilityNodeInfo.containsExactMarker(marker: String): Boolean {
        val candidates = findAccessibilityNodeInfosByText(marker)
        return try {
            candidates.any { candidate ->
                candidate.text?.toString() == marker ||
                    candidate.contentDescription?.toString() == marker
            }
        } finally {
            candidates.forEach { it.recycle() }
        }
    }

    private fun performPopupAction(uiAutomation: UiAutomation): Boolean {
        val windows = uiAutomation.windows.orEmpty()
        try {
            windows.forEach { window ->
                val windowRoot = window.root
                try {
                    if (windowRoot != null && performPopupAction(windowRoot)) {
                        return true
                    }
                } finally {
                    windowRoot?.recycle()
                }
            }
        } finally {
            windows.forEach { it.recycle() }
        }
        return false
    }

    private fun performPopupAction(root: AccessibilityNodeInfo): Boolean {
        val candidates = root.findAccessibilityNodeInfosByText(
            PopupWindowFixtureActivity.POPUP_ACTION_MARKER,
        )
        return try {
            val action = candidates.firstOrNull { candidate ->
                candidate.text?.toString() == PopupWindowFixtureActivity.POPUP_ACTION_MARKER ||
                    candidate.contentDescription?.toString() ==
                    PopupWindowFixtureActivity.POPUP_ACTION_MARKER
            }
            action?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        } finally {
            candidates.forEach { it.recycle() }
        }
    }

    private data class UiSnapshot(
        val activeRoot: RootSnapshot,
        val windows: List<WindowSnapshot>,
    )

    private data class RootSnapshot(
        val windowId: Int,
        val containsUnderlyingMarker: Boolean,
        val containsPopupMarker: Boolean,
        val containsPopupActionMarker: Boolean,
        val containsActionClickedMarker: Boolean,
    ) {
        companion object {
            val EMPTY = RootSnapshot(
                windowId = UNDEFINED_WINDOW_ID,
                containsUnderlyingMarker = false,
                containsPopupMarker = false,
                containsPopupActionMarker = false,
                containsActionClickedMarker = false,
            )
        }
    }

    private data class WindowSnapshot(
        val id: Int,
        val type: Int,
        val layer: Int,
        val isActive: Boolean,
        val isFocused: Boolean,
        val containsUnderlyingMarker: Boolean,
        val containsPopupMarker: Boolean,
        val containsPopupActionMarker: Boolean,
    )

    private companion object {
        const val WINDOW_TIMEOUT_MS = 10_000L
        const val WINDOW_POLL_INTERVAL_MS = 100L
        const val UNDEFINED_WINDOW_ID = -1
    }
}
