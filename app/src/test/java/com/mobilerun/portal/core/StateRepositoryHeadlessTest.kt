package com.mobilerun.portal.core

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.mobilerun.portal.service.MobilerunAccessibilityService
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.runs
import io.mockk.unmockkObject
import io.mockk.verify
import io.mockk.verifyOrder
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class StateRepositoryHeadlessTest {
    @Test
    fun nullServiceKeepsHeadlessBehavior() {
        val repository = StateRepository(service = null)
        val phoneState = repository.getPhoneState()

        assertFalse(repository.hasAccessibilityService)
        assertFalse(repository.hasActiveRoot())
        assertTrue(repository.getVisibleElements().isEmpty())
        assertNull(repository.getFullTree(filter = true))
        assertFalse(repository.setOverlayVisible(true))
        assertFalse(repository.inputText("hello", clear = true))
        assertNull(phoneState.packageName)
        assertFalse(phoneState.keyboardVisible)
        assertTrue(repository.takeScreenshot(hideOverlay = false).isCompletedExceptionally)
    }

    @Test
    fun fullTreeAppendsPopupAfterPrimaryNativeChildren() {
        val service = mockk<MobilerunAccessibilityService>()
        val activeRoot = root(windowId = 10)
        val popupRoot = root(windowId = 20)
        val activeWindow = window(
            id = 10,
            layer = 1,
            type = AccessibilityWindowInfo.TYPE_APPLICATION,
            root = activeRoot,
        )
        val popupWindow = window(
            id = 20,
            layer = 5,
            type = AccessibilityWindowInfo.TYPE_APPLICATION,
            root = popupRoot,
        )
        val nativeChild = tree("native-child")
        val primary = tree("activity", childCount = 1, children = listOf(nativeChild))
        val popup = tree("popup")

        every { service.rootInActiveWindow } returns activeRoot
        every { service.windows } returns listOf(activeWindow, popupWindow)

        mockkObject(AccessibilityTreeBuilder)
        try {
            every {
                AccessibilityTreeBuilder.buildFullAccessibilityTreeJson(activeRoot, null)
            } returns primary
            every {
                AccessibilityTreeBuilder.buildFullAccessibilityTreeJson(popupRoot, null)
            } returns popup

            val result = StateRepository(service).getFullTree(filter = false)

            assertSame(primary, result)
            assertEquals(1, result!!.getInt("childCount"))
            val children = result.getJSONArray("children")
            assertEquals(2, children.length())
            assertSame(nativeChild, children.getJSONObject(0))
            assertSame(popup, children.getJSONObject(1))
            verifyOrder {
                AccessibilityTreeBuilder.buildFullAccessibilityTreeJson(activeRoot, null)
                AccessibilityTreeBuilder.buildFullAccessibilityTreeJson(popupRoot, null)
            }
            verify(exactly = 0) { activeWindow.root }
            verify { activeWindow.recycle() }
            verify { popupWindow.recycle() }
        } finally {
            unmockkObject(AccessibilityTreeBuilder)
        }
    }

    @Test
    fun fullTreeWithFocusableActivePopupExcludesLowerLayerActivityRoot() {
        val service = mockk<MobilerunAccessibilityService>()
        val activityRoot = root(windowId = 10)
        val popupRoot = root(windowId = 20)
        val activityWindow = window(
            id = 10,
            layer = 1,
            type = AccessibilityWindowInfo.TYPE_APPLICATION,
            root = activityRoot,
        )
        val popupWindow = window(
            id = 20,
            layer = 5,
            type = AccessibilityWindowInfo.TYPE_APPLICATION,
            root = popupRoot,
        )
        val popup = tree("focusable-popup")

        every { service.rootInActiveWindow } returns popupRoot
        every { service.windows } returns listOf(activityWindow, popupWindow)

        mockkObject(AccessibilityTreeBuilder)
        try {
            every {
                AccessibilityTreeBuilder.buildFullAccessibilityTreeJson(popupRoot, null)
            } returns popup

            val result = StateRepository(service).getFullTree(filter = false)

            assertSame(popup, result)
            verify(exactly = 1) {
                AccessibilityTreeBuilder.buildFullAccessibilityTreeJson(popupRoot, null)
            }
            verify(exactly = 0) {
                AccessibilityTreeBuilder.buildFullAccessibilityTreeJson(activityRoot, any())
            }
            verify(exactly = 0) { activityWindow.root }
            verify(exactly = 0) { activityRoot.recycle() }
        } finally {
            unmockkObject(AccessibilityTreeBuilder)
        }
    }

    @Test
    fun fullTreePromotesFirstUnfilteredExtraAndAppendsLaterRoots() {
        val service = mockk<MobilerunAccessibilityService>()
        val activeRoot = root(windowId = 1)
        val lowerRoot = root(windowId = 2)
        val popupRoot = root(windowId = 3)
        val activeWindow = window(
            id = 1,
            layer = 1,
            type = AccessibilityWindowInfo.TYPE_APPLICATION,
            root = activeRoot,
        )
        val lowerWindow = window(
            id = 2,
            layer = 4,
            type = AccessibilityWindowInfo.TYPE_APPLICATION,
            root = lowerRoot,
        )
        val popupWindow = window(
            id = 3,
            layer = 8,
            type = AccessibilityWindowInfo.TYPE_APPLICATION,
            root = popupRoot,
        )
        val bounds = Rect(0, 0, 100, 100)
        val popupNativeChild = tree("popup-native-child")
        val popup = tree("popup", childCount = 1, children = listOf(popupNativeChild))
        val lower = tree("lower-window")

        every { service.rootInActiveWindow } returns activeRoot
        every { service.windows } returns listOf(lowerWindow, activeWindow, popupWindow)
        every { service.getScreenBounds() } returns bounds

        mockkObject(AccessibilityTreeBuilder)
        try {
            every {
                AccessibilityTreeBuilder.buildFullAccessibilityTreeJson(activeRoot, any())
            } returns null
            every {
                AccessibilityTreeBuilder.buildFullAccessibilityTreeJson(popupRoot, any())
            } returns popup
            every {
                AccessibilityTreeBuilder.buildFullAccessibilityTreeJson(lowerRoot, any())
            } returns lower

            val result = StateRepository(service).getFullTree(filter = true)

            assertSame(popup, result)
            assertEquals(1, result!!.getInt("childCount"))
            val children = result.getJSONArray("children")
            assertEquals(2, children.length())
            assertSame(popupNativeChild, children.getJSONObject(0))
            assertSame(lower, children.getJSONObject(1))
            verifyOrder {
                AccessibilityTreeBuilder.buildFullAccessibilityTreeJson(activeRoot, any())
                AccessibilityTreeBuilder.buildFullAccessibilityTreeJson(popupRoot, any())
                AccessibilityTreeBuilder.buildFullAccessibilityTreeJson(lowerRoot, any())
            }
        } finally {
            unmockkObject(AccessibilityTreeBuilder)
        }
    }

    @Test
    fun fullTreeFallbackSkipsNullRootsAndMergesApplicationBeforeSystem() {
        val service = mockk<MobilerunAccessibilityService>()
        val appRoot = root(windowId = 2)
        val systemRoot = root(windowId = 3)
        val emptyAppWindow = window(
            id = 1,
            layer = 9,
            type = AccessibilityWindowInfo.TYPE_APPLICATION,
            root = null,
        )
        val appWindow = window(
            id = 2,
            layer = 2,
            type = AccessibilityWindowInfo.TYPE_APPLICATION,
            root = appRoot,
        )
        val systemWindow = window(
            id = 3,
            layer = 10,
            type = AccessibilityWindowInfo.TYPE_SYSTEM,
            root = systemRoot,
        )
        val appNativeChild = tree("app-native-child")
        val app = tree("app", childCount = 1, children = listOf(appNativeChild))
        val system = tree("system")

        every { service.rootInActiveWindow } returns null
        every { service.windows } returns listOf(systemWindow, emptyAppWindow, appWindow)

        mockkObject(AccessibilityTreeBuilder)
        try {
            every {
                AccessibilityTreeBuilder.buildFullAccessibilityTreeJson(appRoot, null)
            } returns app
            every {
                AccessibilityTreeBuilder.buildFullAccessibilityTreeJson(systemRoot, null)
            } returns system

            val result = StateRepository(service).getFullTree(filter = false)

            assertSame(app, result)
            val children = result!!.getJSONArray("children")
            assertEquals(2, children.length())
            assertSame(appNativeChild, children.getJSONObject(0))
            assertSame(system, children.getJSONObject(1))
            verify { emptyAppWindow.root }
            verifyOrder {
                AccessibilityTreeBuilder.buildFullAccessibilityTreeJson(appRoot, null)
                AccessibilityTreeBuilder.buildFullAccessibilityTreeJson(systemRoot, null)
            }
            verify { emptyAppWindow.recycle() }
            verify { appWindow.recycle() }
            verify { systemWindow.recycle() }
        } finally {
            unmockkObject(AccessibilityTreeBuilder)
        }
    }

    @Test
    fun fullTreeKeepsSingleRootObjectUnchanged() {
        val service = mockk<MobilerunAccessibilityService>()
        val activeRoot = root(windowId = 7)
        val activeWindow = window(
            id = 7,
            layer = 1,
            type = AccessibilityWindowInfo.TYPE_APPLICATION,
            root = activeRoot,
        )
        val expected = JSONObject().put("unchanged", true)

        every { service.rootInActiveWindow } returns activeRoot
        every { service.windows } returns listOf(activeWindow)

        mockkObject(AccessibilityTreeBuilder)
        try {
            every {
                AccessibilityTreeBuilder.buildFullAccessibilityTreeJson(activeRoot, null)
            } returns expected

            val result = StateRepository(service).getFullTree(filter = false)

            assertSame(expected, result)
            assertFalse(result!!.has("children"))
            verify(exactly = 0) { service.getScreenBounds() }
        } finally {
            unmockkObject(AccessibilityTreeBuilder)
        }
    }

    @Test
    fun hasActiveRootRecyclesEveryResolvedCandidate() {
        val service = mockk<MobilerunAccessibilityService>()
        val activeRoot = root(windowId = 10)
        val popupRoot = root(windowId = 20)
        val activeWindow = window(
            id = 10,
            layer = 1,
            type = AccessibilityWindowInfo.TYPE_APPLICATION,
            root = activeRoot,
        )
        val popupWindow = window(
            id = 20,
            layer = 5,
            type = AccessibilityWindowInfo.TYPE_APPLICATION,
            root = popupRoot,
        )

        every { service.rootInActiveWindow } returns activeRoot
        every { service.windows } returns listOf(activeWindow, popupWindow)

        assertTrue(StateRepository(service).hasActiveRoot())

        verify(exactly = 1) { activeRoot.recycle() }
        verify(exactly = 1) { popupRoot.recycle() }
    }

    @Test
    fun fullTreeRecyclesOnlyUnprocessedRootsWhenBuilderThrows() {
        val service = mockk<MobilerunAccessibilityService>()
        val activeRoot = root(windowId = 1)
        val popupRoot = root(windowId = 2)
        val lowerRoot = root(windowId = 3)
        val activeWindow = window(1, 1, AccessibilityWindowInfo.TYPE_APPLICATION, activeRoot)
        val popupWindow = window(2, 5, AccessibilityWindowInfo.TYPE_APPLICATION, popupRoot)
        val lowerWindow = window(3, 2, AccessibilityWindowInfo.TYPE_APPLICATION, lowerRoot)
        val expectedFailure = IllegalStateException("unexpected builder failure")

        every { service.rootInActiveWindow } returns activeRoot
        every { service.windows } returns listOf(activeWindow, lowerWindow, popupWindow)

        mockkObject(AccessibilityTreeBuilder)
        try {
            every {
                AccessibilityTreeBuilder.buildFullAccessibilityTreeJson(activeRoot, null)
            } throws expectedFailure

            try {
                StateRepository(service).getFullTree(filter = false)
                fail("Expected the builder failure to propagate")
            } catch (actual: IllegalStateException) {
                assertSame(expectedFailure, actual)
            }

            verify(exactly = 0) { activeRoot.recycle() }
            verify(exactly = 1) { popupRoot.recycle() }
            verify(exactly = 1) { lowerRoot.recycle() }
        } finally {
            unmockkObject(AccessibilityTreeBuilder)
        }
    }

    private fun root(windowId: Int): AccessibilityNodeInfo {
        return mockk<AccessibilityNodeInfo>().also { root ->
            every { root.windowId } returns windowId
            every { root.recycle() } just runs
        }
    }

    private fun window(
        id: Int,
        layer: Int,
        type: Int,
        root: AccessibilityNodeInfo?,
    ): AccessibilityWindowInfo {
        return mockk<AccessibilityWindowInfo>().also { window ->
            every { window.id } returns id
            every { window.layer } returns layer
            every { window.type } returns type
            every { window.root } returns root
            every { window.recycle() } just runs
        }
    }

    private fun tree(
        name: String,
        childCount: Int = 0,
        children: List<JSONObject> = emptyList(),
    ): JSONObject {
        return JSONObject().apply {
            put("name", name)
            put("childCount", childCount)
            put("children", JSONArray().apply { children.forEach(::put) })
        }
    }
}
