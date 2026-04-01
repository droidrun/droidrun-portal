package com.droidrun.portal.api

import android.content.Context
import android.content.pm.PackageManager
import android.view.KeyEvent
import com.droidrun.portal.core.StateRepository
import com.droidrun.portal.input.DroidrunKeyboardIME
import com.droidrun.portal.model.PhoneState
import com.droidrun.portal.service.DroidrunAccessibilityService
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class ApiHandlerTest {
    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun keyboardKey_del_usesImeWhenActiveAndSelected() {
        val stateRepo = mockk<StateRepository>(relaxed = true)
        val ime = mockk<DroidrunKeyboardIME>()
        val service = mockk<DroidrunAccessibilityService>(relaxed = true)
        val context = mockk<Context>(relaxed = true)
        every { context.applicationContext } returns context
        val handler = createHandler(stateRepo = stateRepo, ime = ime, context = context)

        mockkObject(DroidrunKeyboardIME.Companion)
        mockkObject(DroidrunAccessibilityService.Companion)
        every { DroidrunKeyboardIME.isAvailable() } returns true
        every { DroidrunAccessibilityService.getInstance() } returns service
        every { DroidrunKeyboardIME.isSelected(context) } returns true
        every { ime.sendKeyEventDirect(KeyEvent.KEYCODE_DEL) } returns true

        assertEquals(ApiResponse.Success("Delete handled"), handler.keyboardKey(KeyEvent.KEYCODE_DEL))
        verify(exactly = 1) { ime.sendKeyEventDirect(KeyEvent.KEYCODE_DEL) }
        verify(exactly = 0) { service.deleteText(any(), any()) }
    }

    @Test
    fun keyboardKey_del_fallsBackToAccessibilityWhenImeDispatchFails() {
        val stateRepo = mockk<StateRepository>(relaxed = true)
        val ime = mockk<DroidrunKeyboardIME>()
        val service = mockk<DroidrunAccessibilityService>(relaxed = true)
        val context = mockk<Context>(relaxed = true)
        every { context.applicationContext } returns context
        val handler = createHandler(stateRepo = stateRepo, ime = ime, context = context)

        mockkObject(DroidrunKeyboardIME.Companion)
        mockkObject(DroidrunAccessibilityService.Companion)
        every { DroidrunKeyboardIME.isAvailable() } returns true
        every { DroidrunAccessibilityService.getInstance() } returns service
        every { DroidrunKeyboardIME.isSelected(context) } returns true
        every { ime.sendKeyEventDirect(KeyEvent.KEYCODE_DEL) } returns false
        every { service.deleteText(1, false) } returns true

        assertEquals(ApiResponse.Success("Delete handled"), handler.keyboardKey(KeyEvent.KEYCODE_DEL))
        verify(exactly = 1) { ime.sendKeyEventDirect(KeyEvent.KEYCODE_DEL) }
        verify(exactly = 1) { service.deleteText(1, false) }
    }

    @Test
    fun keyboardKey_del_usesImeEvenWhenAccessibilityServiceIsUnavailable() {
        val stateRepo = mockk<StateRepository>(relaxed = true)
        val ime = mockk<DroidrunKeyboardIME>()
        val context = mockk<Context>(relaxed = true)
        every { context.applicationContext } returns context
        val handler = createHandler(stateRepo = stateRepo, ime = ime, context = context)

        mockkObject(DroidrunKeyboardIME.Companion)
        mockkObject(DroidrunAccessibilityService.Companion)
        every { DroidrunKeyboardIME.isAvailable() } returns true
        every { DroidrunAccessibilityService.getInstance() } returns null
        every { DroidrunKeyboardIME.isSelected(context) } returns true
        every { ime.sendKeyEventDirect(KeyEvent.KEYCODE_DEL) } returns true

        assertEquals(ApiResponse.Success("Delete handled"), handler.keyboardKey(KeyEvent.KEYCODE_DEL))
        verify(exactly = 1) { ime.sendKeyEventDirect(KeyEvent.KEYCODE_DEL) }
    }

    @Test
    fun keyboardKey_forwardDelete_usesAccessibilityPath() {
        val stateRepo = mockk<StateRepository>(relaxed = true)
        val ime = mockk<DroidrunKeyboardIME>()
        val service = mockk<DroidrunAccessibilityService>(relaxed = true)
        val handler = createHandler(stateRepo = stateRepo, ime = ime)

        mockkObject(DroidrunAccessibilityService.Companion)
        every { DroidrunAccessibilityService.getInstance() } returns service
        every { service.deleteText(1, true) } returns true

        assertEquals(
            ApiResponse.Success("Forward delete handled"),
            handler.keyboardKey(KeyEvent.KEYCODE_FORWARD_DEL),
        )
        verify(exactly = 1) { service.deleteText(1, true) }
        verify(exactly = 0) { ime.sendKeyEventDirect(any()) }
    }

    @Test
    fun keyboardKey_enter_fallsBackToNewlineWhenImeInactive() {
        val stateRepo = mockk<StateRepository>(relaxed = true)
        val ime = mockk<DroidrunKeyboardIME>(relaxed = true)
        val handler = createHandler(stateRepo = stateRepo, ime = ime)

        every {
            stateRepo.getPhoneState()
        } returns PhoneState(
            focusedElement = null,
            keyboardVisible = true,
            packageName = "com.example",
            appName = "Example",
            isEditable = true,
            activityName = "MainActivity",
        )
        every { stateRepo.inputText("\n", false) } returns true

        assertEquals(
            ApiResponse.Success("Newline inserted via Accessibility"),
            handler.keyboardKey(KeyEvent.KEYCODE_ENTER),
        )
        verify(exactly = 1) { stateRepo.inputText("\n", false) }
    }

    private fun createHandler(
        stateRepo: StateRepository,
        ime: DroidrunKeyboardIME?,
        context: Context = mockk(relaxed = true),
    ): ApiHandler {
        val packageManager = mockk<PackageManager>(relaxed = true)

        return ApiHandler(
            stateRepo = stateRepo,
            getKeyboardIME = { ime },
            getPackageManager = { packageManager },
            appVersionProvider = { "test-version" },
            context = context,
        )
    }
}
