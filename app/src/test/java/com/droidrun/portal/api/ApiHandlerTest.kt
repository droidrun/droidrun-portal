package com.droidrun.portal.api

import android.content.Context
import android.content.pm.PackageManager
import android.view.KeyEvent
import com.droidrun.portal.core.StateRepository
import com.droidrun.portal.input.DroidrunKeyboardIME
import com.droidrun.portal.model.PhoneState
import com.droidrun.portal.service.DroidrunAccessibilityService
import com.droidrun.portal.streaming.WebRtcManager
import io.mockk.Runs
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.just
import io.mockk.unmockkAll
import io.mockk.verify
import org.json.JSONArray
import org.json.JSONObject
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

    @Test
    fun handleWebRtcOffer_acceptsPendingSessionWithoutStreamActiveGate() {
        val stateRepo = mockk<StateRepository>(relaxed = true)
        val context = mockk<Context>(relaxed = true)
        every { context.applicationContext } returns context
        val handler = createHandler(stateRepo = stateRepo, ime = null, context = context)
        val manager = mockk<WebRtcManager>(relaxed = true)

        mockkObject(WebRtcManager.Companion)
        every { WebRtcManager.getInstance(context) } returns manager
        every { manager.isCurrentSession("session-1") } returns true
        every { manager.handleOffer("offer-sdp", "session-1") } just Runs

        assertEquals(
            ApiResponse.Success("SDP Offer processed, answer will be sent"),
            handler.handleWebRtcOffer("offer-sdp", "session-1"),
        )
        verify(exactly = 1) { manager.isCurrentSession("session-1") }
        verify(exactly = 0) { manager.isStreamActive() }
        verify(exactly = 1) { manager.handleOffer("offer-sdp", "session-1") }
    }

    @Test
    fun handleWebRtcIce_acceptsPendingSessionWithoutStreamActiveGate() {
        val stateRepo = mockk<StateRepository>(relaxed = true)
        val context = mockk<Context>(relaxed = true)
        every { context.applicationContext } returns context
        val handler = createHandler(stateRepo = stateRepo, ime = null, context = context)
        val manager = mockk<WebRtcManager>(relaxed = true)

        mockkObject(WebRtcManager.Companion)
        every { WebRtcManager.getInstance(context) } returns manager
        every { manager.isCurrentSession("session-1") } returns true
        every {
            manager.handleIceCandidate(any(), "session-1")
        } just Runs

        assertEquals(
            ApiResponse.Success("ICE Candidate processed"),
            handler.handleWebRtcIce("candidate", "0", 0, "session-1"),
        )
        verify(exactly = 1) { manager.isCurrentSession("session-1") }
        verify(exactly = 0) { manager.isStreamActive() }
        verify(exactly = 1) { manager.handleIceCandidate(any(), "session-1") }
    }

    @Test
    fun connectWebRtc_reusesActiveCapture() {
        val stateRepo = mockk<StateRepository>(relaxed = true)
        val context = mockk<Context>(relaxed = true)
        every { context.applicationContext } returns context
        val handler = createHandler(stateRepo = stateRepo, ime = null, context = context)
        val manager = mockk<WebRtcManager>(relaxed = true)

        mockkObject(WebRtcManager.Companion)
        every { WebRtcManager.getInstance(context) } returns manager
        every { manager.isCaptureActive() } returns true
        every {
            manager.startStreamWithExistingCapture(720, 1280, 30, "session-1", true)
        } just Runs

        val response =
            handler.connectWebRtc(
                JSONObject().apply {
                    put("sessionId", "session-1")
                    put("iceServers", JSONArray())
                },
            )

        assertEquals(ApiResponse.Success("reusing_capture"), response)
        verify(exactly = 1) { manager.setStreamRequestId("session-1") }
        verify(exactly = 1) { manager.setPendingIceServers(any()) }
        verify(exactly = 1) {
            manager.startStreamWithExistingCapture(720, 1280, 30, "session-1", true)
        }
    }

    @Test
    fun handleWebRtcRtcConfiguration_returnsRtcConfigurationAndStartsSession() {
        val stateRepo = mockk<StateRepository>(relaxed = true)
        val context = mockk<Context>(relaxed = true)
        every { context.applicationContext } returns context
        val handler = createHandler(stateRepo = stateRepo, ime = null, context = context)
        val manager = mockk<WebRtcManager>(relaxed = true)

        mockkObject(WebRtcManager.Companion)
        every { WebRtcManager.getInstance(context) } returns manager
        every { manager.isCaptureActive() } returns true
        every {
            manager.startStreamWithExistingCapture(720, 1280, 30, "session-1", true)
        } just Runs

        val response =
            handler.handleWebRtcRtcConfiguration(
                JSONObject().apply {
                    put("sessionId", "session-1")
                    put("iceServers", JSONArray())
                },
            )

        val success = response as ApiResponse.Success
        val result = success.data as JSONObject
        assertEquals(0, result.getJSONObject("rtcConfiguration").getJSONArray("iceServers").length())
        verify(exactly = 1) { manager.setStreamRequestId("session-1") }
        verify(exactly = 1) {
            manager.startStreamWithExistingCapture(720, 1280, 30, "session-1", true)
        }
    }

    @Test
    fun handleWebRtcRequestFrame_andKeepAlive_routeToManager() {
        val stateRepo = mockk<StateRepository>(relaxed = true)
        val context = mockk<Context>(relaxed = true)
        every { context.applicationContext } returns context
        val handler = createHandler(stateRepo = stateRepo, ime = null, context = context)
        val manager = mockk<WebRtcManager>(relaxed = true)

        mockkObject(WebRtcManager.Companion)
        every { WebRtcManager.getInstance(context) } returns manager
        every { manager.handleRequestFrame("session-1") } just Runs
        every { manager.handleKeepAlive("session-1") } just Runs

        assertEquals(
            ApiResponse.Success("request_frame_ack"),
            handler.handleWebRtcRequestFrame("session-1"),
        )
        assertEquals(
            ApiResponse.Success("keep_alive_ack"),
            handler.handleWebRtcKeepAlive("session-1"),
        )
        verify(exactly = 1) { manager.handleRequestFrame("session-1") }
        verify(exactly = 1) { manager.handleKeepAlive("session-1") }
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
