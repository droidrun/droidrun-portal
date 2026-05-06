package com.mobilerun.portal.api

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.view.KeyEvent
import com.mobilerun.portal.core.StateRepository
import com.mobilerun.portal.input.MobilerunKeyboardIME
import com.mobilerun.portal.keepalive.KeepAliveController
import com.mobilerun.portal.keepalive.KeepAliveStartupException
import com.mobilerun.portal.model.PhoneState
import com.mobilerun.portal.service.MobilerunAccessibilityService
import com.mobilerun.portal.service.ReverseConnectionService
import com.mobilerun.portal.streaming.WebRtcManager
import io.mockk.Runs
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.spyk
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
    fun accessibilityReads_returnUnavailableWhenStateRepoHasNoService() {
        val handler = createHandler(stateRepo = StateRepository(service = null), ime = null)
        val expected = ApiResponse.Error("Accessibility service not available")

        assertEquals(expected, handler.getTree())
        assertEquals(expected, handler.getTreeFull(filter = true))
        assertEquals(expected, handler.getPhoneState())
        assertEquals(expected, handler.getState())
        assertEquals(expected, handler.getStateFull(filter = true))
    }

    @Test
    fun nonAccessibilityReads_stillWorkWhenStateRepoHasNoService() {
        val handler = createHandler(stateRepo = StateRepository(service = null), ime = null)

        assertEquals(ApiResponse.Success("pong"), handler.ping())
        assertEquals(ApiResponse.Success("test-version"), handler.getVersion())
    }

    @Test
    fun startApp_requiresAccessibilityWhenStateRepoHasNoService() {
        val context = mockk<Context>(relaxed = true)
        val packageManager = mockk<PackageManager>(relaxed = true)
        val handler = createHandler(
            stateRepo = StateRepository(service = null),
            ime = null,
            context = context,
            packageManager = packageManager,
        )

        assertEquals(
            ApiResponse.Error("App launch requires Accessibility service"),
            handler.startApp("com.example"),
        )

        verify(exactly = 0) { context.startActivity(any()) }
        verify(exactly = 0) { packageManager.getLaunchIntentForPackage(any()) }
    }

    @Test
    fun startApp_explicitActivityRequiresAccessibilityWhenStateRepoHasNoService() {
        val context = mockk<Context>(relaxed = true)
        val packageManager = mockk<PackageManager>(relaxed = true)
        val handler = createHandler(
            stateRepo = StateRepository(service = null),
            ime = null,
            context = context,
            packageManager = packageManager,
        )

        assertEquals(
            ApiResponse.Error("App launch requires Accessibility service"),
            handler.startApp("com.example", ".MainActivity"),
        )

        verify(exactly = 0) { context.startActivity(any()) }
        verify(exactly = 0) { packageManager.getLaunchIntentForPackage(any()) }
    }

    @Test
    fun startApp_usesHandlerContextWhenStateRepoHasService() {
        val context = mockk<Context>(relaxed = true)
        val packageManager = mockk<PackageManager>(relaxed = true)
        val stateRepo = mockk<StateRepository>(relaxed = true)
        val launchIntent = mockk<Intent>(relaxed = true)
        every { stateRepo.hasAccessibilityService } returns true
        every { packageManager.getLaunchIntentForPackage("com.example") } returns launchIntent
        every { launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) } returns launchIntent
        val handler = createHandler(
            stateRepo = stateRepo,
            ime = null,
            context = context,
            packageManager = packageManager,
        )

        assertEquals(ApiResponse.Success("Started app com.example"), handler.startApp("com.example"))

        verify(exactly = 1) { launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        verify(exactly = 1) { context.startActivity(launchIntent) }
    }

    @Test
    fun startApp_explicitActivityUsesHandlerContextWhenStateRepoHasService() {
        val context = mockk<Context>(relaxed = true)
        val packageManager = mockk<PackageManager>(relaxed = true)
        val stateRepo = mockk<StateRepository>(relaxed = true)
        every { stateRepo.hasAccessibilityService } returns true
        mockkConstructor(Intent::class)
        every {
            anyConstructed<Intent>().setClassName("com.example", "com.example.MainActivity")
        } returns mockk(relaxed = true)
        every { anyConstructed<Intent>().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) } returns mockk(relaxed = true)
        val handler = createHandler(
            stateRepo = stateRepo,
            ime = null,
            context = context,
            packageManager = packageManager,
        )

        assertEquals(
            ApiResponse.Success("Started app com.example"),
            handler.startApp("com.example", ".MainActivity"),
        )

        verify(exactly = 1) {
            anyConstructed<Intent>().setClassName("com.example", "com.example.MainActivity")
        }
        verify(exactly = 1) { anyConstructed<Intent>().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        verify(exactly = 1) { context.startActivity(any()) }
        verify(exactly = 0) { packageManager.getLaunchIntentForPackage(any()) }
    }

    @Test
    fun keyboardKey_del_usesImeWhenActiveAndSelected() {
        val stateRepo = mockk<StateRepository>(relaxed = true)
        val ime = mockk<MobilerunKeyboardIME>()
        val service = mockk<MobilerunAccessibilityService>(relaxed = true)
        val context = mockk<Context>(relaxed = true)
        every { context.applicationContext } returns context
        val handler = createHandler(stateRepo = stateRepo, ime = ime, context = context)

        mockkObject(MobilerunKeyboardIME.Companion)
        mockkObject(MobilerunAccessibilityService.Companion)
        every { MobilerunKeyboardIME.isAvailable() } returns true
        every { MobilerunAccessibilityService.getInstance() } returns service
        every { MobilerunKeyboardIME.isSelected(context) } returns true
        every { ime.sendKeyEventDirect(KeyEvent.KEYCODE_DEL) } returns true

        assertEquals(ApiResponse.Success("Delete handled"), handler.keyboardKey(KeyEvent.KEYCODE_DEL))
        verify(exactly = 1) { ime.sendKeyEventDirect(KeyEvent.KEYCODE_DEL) }
        verify(exactly = 0) { service.deleteText(any(), any()) }
    }

    @Test
    fun keyboardKey_del_fallsBackToAccessibilityWhenImeDispatchFails() {
        val stateRepo = mockk<StateRepository>(relaxed = true)
        val ime = mockk<MobilerunKeyboardIME>()
        val service = mockk<MobilerunAccessibilityService>(relaxed = true)
        val context = mockk<Context>(relaxed = true)
        every { context.applicationContext } returns context
        val handler = createHandler(stateRepo = stateRepo, ime = ime, context = context)

        mockkObject(MobilerunKeyboardIME.Companion)
        mockkObject(MobilerunAccessibilityService.Companion)
        every { MobilerunKeyboardIME.isAvailable() } returns true
        every { MobilerunAccessibilityService.getInstance() } returns service
        every { MobilerunKeyboardIME.isSelected(context) } returns true
        every { ime.sendKeyEventDirect(KeyEvent.KEYCODE_DEL) } returns false
        every { service.deleteText(1, false) } returns true

        assertEquals(ApiResponse.Success("Delete handled"), handler.keyboardKey(KeyEvent.KEYCODE_DEL))
        verify(exactly = 1) { ime.sendKeyEventDirect(KeyEvent.KEYCODE_DEL) }
        verify(exactly = 1) { service.deleteText(1, false) }
    }

    @Test
    fun keyboardKey_del_usesImeEvenWhenAccessibilityServiceIsUnavailable() {
        val stateRepo = mockk<StateRepository>(relaxed = true)
        val ime = mockk<MobilerunKeyboardIME>()
        val context = mockk<Context>(relaxed = true)
        every { context.applicationContext } returns context
        val handler = createHandler(stateRepo = stateRepo, ime = ime, context = context)

        mockkObject(MobilerunKeyboardIME.Companion)
        mockkObject(MobilerunAccessibilityService.Companion)
        every { MobilerunKeyboardIME.isAvailable() } returns true
        every { MobilerunAccessibilityService.getInstance() } returns null
        every { MobilerunKeyboardIME.isSelected(context) } returns true
        every { ime.sendKeyEventDirect(KeyEvent.KEYCODE_DEL) } returns true

        assertEquals(ApiResponse.Success("Delete handled"), handler.keyboardKey(KeyEvent.KEYCODE_DEL))
        verify(exactly = 1) { ime.sendKeyEventDirect(KeyEvent.KEYCODE_DEL) }
    }

    @Test
    fun keyboardKey_forwardDelete_usesAccessibilityPath() {
        val stateRepo = mockk<StateRepository>(relaxed = true)
        val ime = mockk<MobilerunKeyboardIME>()
        val service = mockk<MobilerunAccessibilityService>(relaxed = true)
        val handler = createHandler(stateRepo = stateRepo, ime = ime)

        mockkObject(MobilerunAccessibilityService.Companion)
        every { MobilerunAccessibilityService.getInstance() } returns service
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
        val ime = mockk<MobilerunKeyboardIME>(relaxed = true)
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
    fun isOverlayVisible_returnsVisibleFlag() {
        val stateRepo = mockk<StateRepository>(relaxed = true)
        every { stateRepo.isOverlayVisible() } returns true
        val handler = createHandler(stateRepo = stateRepo, ime = null)

        val response = handler.isOverlayVisible() as ApiResponse.RawObject

        assertEquals(true, response.json.getBoolean("visible"))
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
        val reverseService = mockk<ReverseConnectionService>(relaxed = true)

        mockkObject(WebRtcManager.Companion)
        mockkObject(ReverseConnectionService.Companion)
        every { WebRtcManager.getInstance(context) } returns manager
        every { ReverseConnectionService.getInstance() } returns reverseService
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
        verify(exactly = 1) { manager.setReverseConnectionService(reverseService) }
        verify(exactly = 1) { manager.setPendingIceServers(any()) }
        verify(exactly = 1) {
            manager.startStreamWithExistingCapture(720, 1280, 30, "session-1", true)
        }
    }

    @Test
    fun startStream_reusesActiveCaptureAndBindsReverseService() {
        val stateRepo = mockk<StateRepository>(relaxed = true)
        val context = mockk<Context>(relaxed = true)
        every { context.applicationContext } returns context
        val handler = createHandler(stateRepo = stateRepo, ime = null, context = context)
        val manager = mockk<WebRtcManager>(relaxed = true)
        val reverseService = mockk<ReverseConnectionService>(relaxed = true)

        mockkObject(WebRtcManager.Companion)
        mockkObject(ReverseConnectionService.Companion)
        every { WebRtcManager.getInstance(context) } returns manager
        every { ReverseConnectionService.getInstance() } returns reverseService
        every { manager.isCaptureActive() } returns true
        every {
            manager.startStreamWithExistingCapture(720, 1280, 30, "session-1", false)
        } just Runs

        val response =
            handler.startStream(
                JSONObject().apply {
                    put("sessionId", "session-1")
                    put("iceServers", JSONArray())
                },
            )

        assertEquals(ApiResponse.Success("reusing_capture"), response)
        verify(exactly = 1) { manager.setStreamRequestId("session-1") }
        verify(exactly = 1) { manager.setReverseConnectionService(reverseService) }
        verify(exactly = 1) { manager.setPendingIceServers(any()) }
        verify(exactly = 1) {
            manager.startStreamWithExistingCapture(720, 1280, 30, "session-1", false)
        }
    }

    @Test
    fun connectWebRtc_withoutActiveCapture_fallsBackToStartStream() {
        val stateRepo = mockk<StateRepository>(relaxed = true)
        val context = mockk<Context>(relaxed = true)
        every { context.applicationContext } returns context
        val handler = spyk(createHandler(stateRepo = stateRepo, ime = null, context = context))
        val manager = mockk<WebRtcManager>(relaxed = true)

        mockkObject(WebRtcManager.Companion)
        every { WebRtcManager.getInstance(context) } returns manager
        every { manager.isCaptureActive() } returns false
        every { handler.startStream(any()) } returns ApiResponse.Success("prompting_user")

        val response =
            handler.connectWebRtc(
                JSONObject().apply {
                    put("sessionId", "session-1")
                    put("iceServers", JSONArray())
                },
            )

        assertEquals(ApiResponse.Success("prompting_user"), response)
        verify(exactly = 1) { handler.startStream(any()) }
        verify(exactly = 0) {
            manager.startStreamWithExistingCapture(any(), any(), any(), any(), any())
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

    @Test
    fun setScreenKeepAwakeEnabled_routesThroughControllerAndReturnsStatus() {
        val stateRepo = mockk<StateRepository>(relaxed = true)
        val context = mockk<Context>(relaxed = true)
        every { context.applicationContext } returns context
        val handler = createHandler(stateRepo = stateRepo, ime = null, context = context)
        val statusJson =
            JSONObject().apply {
                put("enabled", true)
                put("serviceActive", true)
                put("interactive", true)
                put("deviceLocked", false)
                put("lastRecoveryAtMs", 111L)
                put("consecutiveRecoveryFailures", 0)
                put("degradedReason", JSONObject.NULL)
            }

        mockkObject(KeepAliveController)
        every { KeepAliveController.setEnabled(context, true) } just Runs
        every { KeepAliveController.getMutationResultStatusJson(context, true) } returns statusJson

        val response = handler.setScreenKeepAwakeEnabled(true) as ApiResponse.RawObject

        assertEquals(true, response.json.getBoolean("enabled"))
        assertEquals(true, response.json.getBoolean("serviceActive"))
        verify(exactly = 1) { KeepAliveController.setEnabled(context, true) }
        verify(exactly = 1) { KeepAliveController.getMutationResultStatusJson(context, true) }
    }

    @Test
    fun setScreenKeepAwakeEnabled_returnsDisabledTargetStateAfterSuccessfulDisable() {
        val stateRepo = mockk<StateRepository>(relaxed = true)
        val context = mockk<Context>(relaxed = true)
        every { context.applicationContext } returns context
        val handler = createHandler(stateRepo = stateRepo, ime = null, context = context)
        val statusJson =
            JSONObject().apply {
                put("enabled", false)
                put("serviceActive", false)
                put("interactive", true)
                put("deviceLocked", false)
                put("lastRecoveryAtMs", 222L)
                put("consecutiveRecoveryFailures", 1)
                put("degradedReason", "recovery_throttled")
            }

        mockkObject(KeepAliveController)
        every { KeepAliveController.setEnabled(context, false) } just Runs
        every { KeepAliveController.getMutationResultStatusJson(context, false) } returns statusJson

        val response = handler.setScreenKeepAwakeEnabled(false) as ApiResponse.RawObject

        assertEquals(false, response.json.getBoolean("enabled"))
        assertEquals(false, response.json.getBoolean("serviceActive"))
        assertEquals("recovery_throttled", response.json.getString("degradedReason"))
        verify(exactly = 1) { KeepAliveController.setEnabled(context, false) }
        verify(exactly = 1) { KeepAliveController.getMutationResultStatusJson(context, false) }
    }

    @Test
    fun getScreenKeepAwakeStatus_returnsControllerStatusWithoutMutation() {
        val stateRepo = mockk<StateRepository>(relaxed = true)
        val context = mockk<Context>(relaxed = true)
        every { context.applicationContext } returns context
        val handler = createHandler(stateRepo = stateRepo, ime = null, context = context)
        val statusJson =
            JSONObject().apply {
                put("enabled", false)
                put("serviceActive", false)
                put("interactive", true)
                put("deviceLocked", false)
                put("lastRecoveryAtMs", 0L)
                put("consecutiveRecoveryFailures", 2)
                put("degradedReason", "recovery_throttled")
            }

        mockkObject(KeepAliveController)
        every { KeepAliveController.getStatusJson(context) } returns statusJson

        val response = handler.getScreenKeepAwakeStatus() as ApiResponse.RawObject

        assertEquals(false, response.json.getBoolean("enabled"))
        assertEquals("recovery_throttled", response.json.getString("degradedReason"))
        verify(exactly = 0) { KeepAliveController.setEnabled(any(), any()) }
        verify(exactly = 1) { KeepAliveController.getStatusJson(context) }
    }

    @Test
    fun setScreenKeepAwakeEnabled_returnsErrorWhenStartupIsRejected() {
        val stateRepo = mockk<StateRepository>(relaxed = true)
        val context = mockk<Context>(relaxed = true)
        every { context.applicationContext } returns context
        val handler = createHandler(stateRepo = stateRepo, ime = null, context = context)

        mockkObject(KeepAliveController)
        every { KeepAliveController.setEnabled(context, true) } throws
            KeepAliveStartupException("foreground_service_start_not_allowed")

        val response = handler.setScreenKeepAwakeEnabled(true)

        assertEquals(
            ApiResponse.Error("foreground_service_start_not_allowed"),
            response,
        )
        verify(exactly = 1) { KeepAliveController.setEnabled(context, true) }
        verify(exactly = 0) { KeepAliveController.getStatusJson(any()) }
        verify(exactly = 0) { KeepAliveController.getMutationResultStatusJson(any(), any()) }
    }

    private fun createHandler(
        stateRepo: StateRepository,
        ime: MobilerunKeyboardIME?,
        context: Context = mockk(relaxed = true),
        packageManager: PackageManager = mockk(relaxed = true),
    ): ApiHandler {
        return ApiHandler(
            stateRepo = stateRepo,
            getKeyboardIME = { ime },
            getPackageManager = { packageManager },
            appVersionProvider = { "test-version" },
            context = context,
        )
    }
}
