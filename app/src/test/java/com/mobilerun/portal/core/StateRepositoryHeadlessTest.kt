package com.mobilerun.portal.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StateRepositoryHeadlessTest {
    @Test
    fun nullServiceKeepsHeadlessBehavior() {
        val repository = StateRepository(service = null)
        val phoneState = repository.getPhoneState()

        assertFalse(repository.hasAccessibilityService)
        assertTrue(repository.getVisibleElements().isEmpty())
        assertNull(repository.getFullTree(filter = true))
        assertFalse(repository.setOverlayVisible(true))
        assertFalse(repository.inputText("hello", clear = true))
        assertNull(phoneState.packageName)
        assertFalse(phoneState.keyboardVisible)
        assertTrue(repository.takeScreenshot(hideOverlay = false).isCompletedExceptionally)
    }
}
