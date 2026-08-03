package com.mobilerun.portal.core

import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.mobilerun.portal.service.MobilerunAccessibilityService
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class AccessibilityRootResolverTest {
    @Test
    fun activeRootStaysFirstAndApplicationExtrasUseDeterministicOrder() {
        val service = mockk<MobilerunAccessibilityService>()
        val activeRoot = root(windowId = 10)
        val popupHighId3 = root()
        val popupHighId2 = root()
        val popupLow = root()
        val activeWindow = window(id = 10, layer = 4, root = activeRoot)
        val highId3Window = window(id = 3, layer = 8, root = popupHighId3)
        val highId2Window = window(id = 2, layer = 8, root = popupHighId2)
        val lowWindow = window(id = 4, layer = 5, root = popupLow)
        val passiveSystemWindow = window(
            id = 99,
            layer = 20,
            type = AccessibilityWindowInfo.TYPE_SYSTEM,
            root = root(),
        )

        every { service.rootInActiveWindow } returns activeRoot
        every { service.windows } returns listOf(
            lowWindow,
            passiveSystemWindow,
            highId3Window,
            activeWindow,
            highId2Window,
        )

        val candidates = AccessibilityRootResolver.resolve(service)

        assertEquals(listOf(10, 2, 3, 4), candidates.map { it.windowId })
        assertEquals(listOf(4, 8, 8, 5), candidates.map { it.layer })
        assertSame(activeRoot, candidates[0].root)
        assertSame(popupHighId2, candidates[1].root)
        assertSame(popupHighId3, candidates[2].root)
        assertSame(popupLow, candidates[3].root)
        verify(exactly = 0) { activeWindow.root }
        verify(exactly = 0) { passiveSystemWindow.root }
        listOf(
            activeWindow,
            highId3Window,
            highId2Window,
            lowWindow,
            passiveSystemWindow,
        ).forEach { verify(exactly = 1) { it.recycle() } }
        candidates.forEach { candidate -> verify(exactly = 0) { candidate.root.recycle() } }
    }

    @Test
    fun missingActiveRootUsesApplicationWindowsBeforeSystemWindows() {
        val service = mockk<MobilerunAccessibilityService>()
        val highAppRoot = root()
        val lowAppRoot = root()
        val systemRoot = root()
        val emptyAppWindow = window(id = 9, layer = 20, root = null)
        val highAppWindow = window(id = 7, layer = 10, root = highAppRoot)
        val lowAppWindow = window(id = 5, layer = 1, root = lowAppRoot)
        val systemWindow = window(
            id = 1,
            layer = 99,
            type = AccessibilityWindowInfo.TYPE_SYSTEM,
            root = systemRoot,
        )

        every { service.rootInActiveWindow } returns null
        every { service.windows } returns listOf(systemWindow, lowAppWindow, emptyAppWindow, highAppWindow)

        val candidates = AccessibilityRootResolver.resolve(service)

        assertEquals(listOf(7, 5, 1), candidates.map { it.windowId })
        assertEquals(listOf(10, 1, 99), candidates.map { it.layer })
        assertSame(highAppRoot, candidates[0].root)
        assertSame(lowAppRoot, candidates[1].root)
        assertSame(systemRoot, candidates[2].root)
        listOf(emptyAppWindow, highAppWindow, lowAppWindow, systemWindow).forEach {
            verify(exactly = 1) { it.recycle() }
        }
    }

    @Test
    fun duplicateWindowIdsAndUndefinedRootIdentityAreReturnedOnlyOnce() {
        val service = mockk<MobilerunAccessibilityService>()
        val activeRoot = root(windowId = -1)
        val otherUndefinedRoot = root(windowId = -1)
        val validRoot = root()
        val duplicateValidRoot = root()
        val activeDuplicateWindow = window(id = -1, layer = 8, root = activeRoot)
        val otherUndefinedWindow = window(id = -1, layer = 7, root = otherUndefinedRoot)
        val validWindow = window(id = 5, layer = 6, root = validRoot)
        val duplicateValidWindow = window(id = 5, layer = 5, root = duplicateValidRoot)

        every { service.rootInActiveWindow } returns activeRoot
        every { service.windows } returns listOf(
            duplicateValidWindow,
            validWindow,
            otherUndefinedWindow,
            activeDuplicateWindow,
        )

        val candidates = AccessibilityRootResolver.resolve(service)

        assertEquals(listOf(-1, -1, 5), candidates.map { it.windowId })
        assertEquals(listOf(8, 7, 6), candidates.map { it.layer })
        assertSame(activeRoot, candidates[0].root)
        assertSame(otherUndefinedRoot, candidates[1].root)
        assertSame(validRoot, candidates[2].root)
        verify(exactly = 0) { duplicateValidWindow.root }
        verify(exactly = 0) { activeRoot.recycle() }
        candidates.forEach { candidate -> verify(exactly = 0) { candidate.root.recycle() } }
        listOf(
            activeDuplicateWindow,
            otherUndefinedWindow,
            validWindow,
            duplicateValidWindow,
        ).forEach { verify(exactly = 1) { it.recycle() } }
    }

    @Test
    fun failingWindowIsSkippedWithoutPreventingLaterCandidatesOrRecycling() {
        val service = mockk<MobilerunAccessibilityService>()
        val failingWindow = window(id = 8, layer = 9, root = null)
        val malformedWindow = mockk<AccessibilityWindowInfo>()
        val goodRoot = root()
        val goodWindow = window(id = 7, layer = 1, root = goodRoot)

        every { service.rootInActiveWindow } returns null
        every { service.windows } returns listOf(failingWindow, malformedWindow, goodWindow)
        every { failingWindow.root } throws RuntimeException("root unavailable")
        every { malformedWindow.id } throws RuntimeException("window unavailable")
        every { malformedWindow.recycle() } just runs

        val candidates = AccessibilityRootResolver.resolve(service)

        assertEquals(listOf(7), candidates.map { it.windowId })
        assertSame(goodRoot, candidates.single().root)
        verify(exactly = 1) { failingWindow.recycle() }
        verify(exactly = 1) { malformedWindow.recycle() }
        verify(exactly = 1) { goodWindow.recycle() }
    }

    @Test
    fun unavailableWindowListKeepsActiveRootWithDefaultLayer() {
        val service = mockk<MobilerunAccessibilityService>()
        val activeRoot = root(windowId = 12)

        every { service.rootInActiveWindow } returns activeRoot
        every { service.windows } throws RuntimeException("windows unavailable")

        val candidate = AccessibilityRootResolver.resolve(service).single()

        assertSame(activeRoot, candidate.root)
        assertEquals(12, candidate.windowId)
        assertEquals(0, candidate.layer)
        verify(exactly = 0) { activeRoot.recycle() }
    }

    private fun root(windowId: Int? = null): AccessibilityNodeInfo {
        return mockk<AccessibilityNodeInfo>().also { node ->
            if (windowId != null) {
                every { node.windowId } returns windowId
            }
            every { node.recycle() } just runs
        }
    }

    private fun window(
        id: Int,
        layer: Int,
        type: Int = AccessibilityWindowInfo.TYPE_APPLICATION,
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
}
