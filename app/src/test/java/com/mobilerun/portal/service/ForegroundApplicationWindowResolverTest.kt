package com.mobilerun.portal.service

import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.mobilerun.portal.events.model.EventType
import com.mobilerun.portal.events.model.PortalEvent
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ForegroundApplicationWindowResolverTest {
    @Test
    fun notificationAndContentEventsCannotReadWindowsOrProduceCandidates() {
        listOf(
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
        ).forEach { eventType ->
            val event = event(type = eventType, windowId = 4, packageName = SYSTEM_UI)
            var windowsRead = false

            val candidate = ForegroundApplicationWindowResolver.resolve(event) {
                windowsRead = true
                emptyList()
            }

            assertNull(candidate)
            assertFalse(windowsRead)
            verify(exactly = 0) { event.windowId }
        }
    }

    @Test
    fun stateChangedUsesRootPackageFromExactActiveApplicationWindow() {
        val event = event(
            type = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            windowId = 7,
            packageName = "untrusted.event.package",
        )
        val root = root(packageName = PORTAL)
        val window = window(id = 7, layer = 3, active = true, root = root)

        val candidate = ForegroundApplicationWindowResolver.resolve(event) { listOf(window) }

        assertEquals(
            ForegroundApplicationWindowResolver.Candidate(PORTAL, 7),
            candidate,
        )
        verify(exactly = 0) { event.packageName }
        verify(exactly = 1) { window.root }
        verify(exactly = 1) { root.packageName }
        verify(exactly = 1) { root.recycle() }
        verify(exactly = 1) { window.recycle() }
    }

    @Test
    fun stateChangedRejectsStaleInactiveAndNonApplicationWindows() {
        val event = event(
            type = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            windowId = 10,
            packageName = SYSTEM_UI,
        )
        val windows = listOf(
            window(id = 10, layer = 9, active = false, focused = false, root = root(SYSTEM_UI)),
            window(id = 11, layer = 8, active = true, root = root("other.app")),
            window(
                id = 10,
                layer = 7,
                type = AccessibilityWindowInfo.TYPE_SYSTEM,
                focused = true,
                root = root(SYSTEM_UI),
            ),
            window(
                id = 10,
                layer = 6,
                type = AccessibilityWindowInfo.TYPE_INPUT_METHOD,
                focused = true,
                root = root("ime.package"),
            ),
            window(
                id = 10,
                layer = 5,
                type = AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY,
                focused = true,
                root = root("overlay.package"),
            ),
        )

        val candidate = ForegroundApplicationWindowResolver.resolve(event) { windows }

        assertNull(candidate)
        windows.forEach { window ->
            verify(exactly = 0) { window.root }
            verify(exactly = 1) { window.recycle() }
        }
        verify(exactly = 0) { event.packageName }
    }

    @Test
    fun stateChangedAcceptsFocusedApplicationWindowThatIsNotActive() {
        val event = event(
            type = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            windowId = 12,
        )
        val root = root("focused.app")
        val window = window(
            id = 12,
            layer = 4,
            active = false,
            focused = true,
            root = root,
        )

        val candidate = ForegroundApplicationWindowResolver.resolve(event) { listOf(window) }

        assertEquals("focused.app", candidate?.packageName)
        verify(exactly = 1) { root.recycle() }
        verify(exactly = 1) { window.recycle() }
    }

    @Test
    fun windowsChangedPrefersFocusedThenLayerThenLowestId() {
        val event = event(
            type = AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            windowId = 2,
            packageName = "event.package",
        )
        val activeRoot = root("active.app")
        val focusedLowRoot = root("focused.low")
        val focusedHighIdRoot = root("focused.high.id")
        val selectedRoot = root("focused.selected")
        val active = window(id = 1, layer = 100, active = true, root = activeRoot)
        val focusedLow = window(id = 8, layer = 3, focused = true, root = focusedLowRoot)
        val focusedHighId = window(id = 3, layer = 8, focused = true, root = focusedHighIdRoot)
        val selected = window(id = 2, layer = 8, focused = true, root = selectedRoot)
        val windows = listOf(active, focusedLow, focusedHighId, selected)

        val candidate = ForegroundApplicationWindowResolver.resolve(event) { windows }

        assertEquals(
            ForegroundApplicationWindowResolver.Candidate("focused.selected", 2),
            candidate,
        )
        verify(exactly = 1) { selected.root }
        verify(exactly = 1) { selectedRoot.recycle() }
        listOf(active, focusedLow, focusedHighId).forEach { window ->
            verify(exactly = 0) { window.root }
        }
        listOf(activeRoot, focusedLowRoot, focusedHighIdRoot).forEach { root ->
            verify(exactly = 0) { root.recycle() }
        }
        windows.forEach { window -> verify(exactly = 1) { window.recycle() } }
        verify(exactly = 0) { event.packageName }
    }

    @Test
    fun windowsChangedUsesHighestLayerAndLowestIdAmongActiveWindows() {
        val event = event(type = AccessibilityEvent.TYPE_WINDOWS_CHANGED, windowId = 5)
        val low = window(id = 1, layer = 1, active = true, root = root("low.app"))
        val highId = window(id = 6, layer = 9, active = true, root = root("high.id.app"))
        val selectedRoot = root("selected.app")
        val selected = window(id = 5, layer = 9, active = true, root = selectedRoot)

        val candidate = ForegroundApplicationWindowResolver.resolve(event) {
            listOf(low, highId, selected)
        }

        assertEquals("selected.app", candidate?.packageName)
        assertEquals(5, candidate?.windowId)
        verify(exactly = 1) { selectedRoot.recycle() }
        listOf(low, highId, selected).forEach { verify(exactly = 1) { it.recycle() } }
    }

    @Test
    fun blankRootPackageFallsBackToExactlyMatchingEventPackage() {
        val event = event(
            type = AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            windowId = 4,
            packageName = " fallback.app ",
        )
        val root = root(packageName = "   ")
        val window = window(id = 4, layer = 2, focused = true, root = root)

        val candidate = ForegroundApplicationWindowResolver.resolve(event) { listOf(window) }

        assertEquals(
            ForegroundApplicationWindowResolver.Candidate("fallback.app", 4),
            candidate,
        )
        verify(exactly = 1) { event.packageName }
        verify(exactly = 1) { root.recycle() }
        verify(exactly = 1) { window.recycle() }
    }

    @Test
    fun blankRootPackageCannotUseEventPackageFromDifferentWindow() {
        val event = event(
            type = AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            windowId = 99,
            packageName = "stale.event.package",
        )
        val root = root(packageName = null)
        val window = window(id = 4, layer = 2, active = true, root = root)

        val candidate = ForegroundApplicationWindowResolver.resolve(event) { listOf(window) }

        assertNull(candidate)
        verify(exactly = 0) { event.packageName }
        verify(exactly = 1) { root.recycle() }
        verify(exactly = 1) { window.recycle() }
    }

    @Test
    fun missingOrFailingRootFailsClosedAndStillRecyclesHandles() {
        val missingEvent = event(AccessibilityEvent.TYPE_WINDOWS_CHANGED, windowId = 1)
        val missingWindow = window(id = 1, layer = 1, active = true, root = null)

        assertNull(
            ForegroundApplicationWindowResolver.resolve(missingEvent) { listOf(missingWindow) },
        )
        verify(exactly = 1) { missingWindow.recycle() }

        val failingEvent = event(AccessibilityEvent.TYPE_WINDOWS_CHANGED, windowId = 2)
        val failingRoot = root("ignored")
        every { failingRoot.packageName } throws RuntimeException("stale root")
        val failingWindow = window(id = 2, layer = 2, active = true, root = failingRoot)

        assertNull(
            ForegroundApplicationWindowResolver.resolve(failingEvent) { listOf(failingWindow) },
        )
        verify(exactly = 1) { failingRoot.recycle() }
        verify(exactly = 1) { failingWindow.recycle() }

        val throwingEvent = event(AccessibilityEvent.TYPE_WINDOWS_CHANGED, windowId = 3)
        val throwingWindow = window(id = 3, layer = 3, active = true, root = null)
        every { throwingWindow.root } throws RuntimeException("window disconnected")

        assertNull(
            ForegroundApplicationWindowResolver.resolve(throwingEvent) { listOf(throwingWindow) },
        )
        verify(exactly = 1) { throwingWindow.recycle() }
    }

    @Test
    fun malformedWindowIsSkippedWithoutBlockingValidWindowOrRecycling() {
        val event = event(AccessibilityEvent.TYPE_WINDOWS_CHANGED, windowId = 5)
        val malformed = mockk<AccessibilityWindowInfo>()
        every { malformed.id } throws RuntimeException("window unavailable")
        every { malformed.recycle() } just runs
        val validRoot = root("valid.app")
        val valid = window(id = 5, layer = 1, active = true, root = validRoot)

        val candidate = ForegroundApplicationWindowResolver.resolve(event) {
            listOf(malformed, valid)
        }

        assertEquals("valid.app", candidate?.packageName)
        verify(exactly = 1) { malformed.recycle() }
        verify(exactly = 1) { valid.recycle() }
        verify(exactly = 1) { validRoot.recycle() }
    }

    @Test
    fun unavailableWindowListAndEventMetadataFailClosed() {
        val event = event(AccessibilityEvent.TYPE_WINDOWS_CHANGED, windowId = 1)

        assertNull(ForegroundApplicationWindowResolver.resolve(event) { null })
        assertNull(
            ForegroundApplicationWindowResolver.resolve(event) {
                throw RuntimeException("service disconnected")
            },
        )

        val badType = mockk<AccessibilityEvent>()
        every { badType.eventType } throws RuntimeException("recycled event")
        assertNull(ForegroundApplicationWindowResolver.resolve(badType) { emptyList() })

        val badWindowId = event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, windowId = 1)
        every { badWindowId.windowId } throws RuntimeException("missing ID")
        assertNull(ForegroundApplicationWindowResolver.resolve(badWindowId) { emptyList() })
    }

    @Test
    fun undefinedWindowDescriptorCannotAdvanceState() {
        val undefinedRoot = root("com.example.undefined")
        val undefinedWindow = window(
            id = -1,
            layer = 10,
            active = true,
            root = undefinedRoot,
        )

        val candidate = ForegroundApplicationWindowResolver.resolve(
            event(AccessibilityEvent.TYPE_WINDOWS_CHANGED, windowId = -1),
        ) {
            listOf(undefinedWindow)
        }

        assertNull(candidate)
        verify(exactly = 0) { undefinedWindow.root }
        verify(exactly = 0) { undefinedRoot.recycle() }
        verify(exactly = 1) { undefinedWindow.recycle() }
    }

    @Test
    fun duplicateWindowIdentityIsInspectedAndRecycledExactlyOnce() {
        val event = event(AccessibilityEvent.TYPE_WINDOWS_CHANGED, windowId = 1)
        val root = root(PORTAL)
        val window = window(id = 1, layer = 1, active = true, root = root)

        val candidate = ForegroundApplicationWindowResolver.resolve(event) {
            listOf(window, window)
        }

        assertEquals(PORTAL, candidate?.packageName)
        verify(exactly = 1) { window.id }
        verify(exactly = 1) { window.root }
        verify(exactly = 1) { root.recycle() }
        verify(exactly = 1) { window.recycle() }
    }

    @Test
    fun recyclingFailuresDoNotDiscardOtherwiseConfirmedCandidate() {
        val event = event(AccessibilityEvent.TYPE_WINDOWS_CHANGED, windowId = 1)
        val root = root(PORTAL)
        every { root.recycle() } throws RuntimeException("root already stale")
        val window = window(id = 1, layer = 1, active = true, root = root)
        every { window.recycle() } throws RuntimeException("window already stale")

        val candidate = ForegroundApplicationWindowResolver.resolve(event) { listOf(window) }

        assertEquals(PORTAL, candidate?.packageName)
        verify(exactly = 1) { root.recycle() }
        verify(exactly = 1) { window.recycle() }
    }

    @Test
    fun portalSystemUiNotificationPortalSequenceEmitsNoFalseTransition() {
        val emitted = mutableListOf<PortalEvent>()
        val tracker = ForegroundApplicationTransitionTracker(emitted::add)
        val portalRoot = root(PORTAL)
        val portalWindow = window(id = 1, layer = 1, active = true, root = portalRoot)
        val portalEvent = event(
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            windowId = 1,
            packageName = PORTAL,
        )
        tracker.advance(
            ForegroundApplicationWindowResolver.resolve(portalEvent) {
                listOf(portalWindow)
            }?.packageName,
        )

        val systemRoot = root(SYSTEM_UI)
        val systemWindow = window(
            id = 2,
            layer = 20,
            type = AccessibilityWindowInfo.TYPE_SYSTEM,
            active = true,
            focused = true,
            root = systemRoot,
        )
        val systemEvent = event(
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            windowId = 2,
            packageName = SYSTEM_UI,
        )
        tracker.advance(
            ForegroundApplicationWindowResolver.resolve(systemEvent) {
                listOf(systemWindow)
            }?.packageName,
        )

        var notificationReadWindows = false
        val notificationEvent = event(
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED,
            windowId = 2,
            packageName = SYSTEM_UI,
        )
        tracker.advance(
            ForegroundApplicationWindowResolver.resolve(notificationEvent) {
                notificationReadWindows = true
                emptyList()
            }?.packageName,
        )

        val returnedPortalRoot = root(PORTAL)
        val returnedPortalWindow = window(
            id = 1,
            layer = 1,
            focused = true,
            root = returnedPortalRoot,
        )
        val returnedPortalEvent = event(
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            windowId = 1,
            packageName = PORTAL,
        )
        tracker.advance(
            ForegroundApplicationWindowResolver.resolve(returnedPortalEvent) {
                listOf(returnedPortalWindow)
            }?.packageName,
        )

        assertEquals(listOf(EventType.APP_ENTERED), emitted.map(PortalEvent::type))
        assertFalse(notificationReadWindows)
        verify(exactly = 1) { portalRoot.recycle() }
        verify(exactly = 1) { portalWindow.recycle() }
        verify(exactly = 0) { systemWindow.root }
        verify(exactly = 0) { systemRoot.recycle() }
        verify(exactly = 1) { systemWindow.recycle() }
        verify(exactly = 1) { returnedPortalRoot.recycle() }
        verify(exactly = 1) { returnedPortalWindow.recycle() }
    }

    private fun event(
        type: Int,
        windowId: Int = -1,
        packageName: String? = null,
    ): AccessibilityEvent {
        return mockk<AccessibilityEvent>().also { event ->
            every { event.eventType } returns type
            every { event.windowId } returns windowId
            every { event.packageName } returns packageName
        }
    }

    private fun root(packageName: String?): AccessibilityNodeInfo {
        return mockk<AccessibilityNodeInfo>().also { root ->
            every { root.packageName } returns packageName
            every { root.recycle() } just runs
        }
    }

    private fun window(
        id: Int,
        layer: Int,
        type: Int = AccessibilityWindowInfo.TYPE_APPLICATION,
        active: Boolean = false,
        focused: Boolean = false,
        root: AccessibilityNodeInfo?,
    ): AccessibilityWindowInfo {
        return mockk<AccessibilityWindowInfo>().also { window ->
            every { window.id } returns id
            every { window.layer } returns layer
            every { window.type } returns type
            every { window.isActive } returns active
            every { window.isFocused } returns focused
            every { window.root } returns root
            every { window.recycle() } just runs
        }
    }

    private companion object {
        const val PORTAL = "com.mobilerun.portal"
        const val SYSTEM_UI = "com.android.systemui"
    }
}
