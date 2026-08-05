package com.mobilerun.portal.api

import android.app.ActivityOptions
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.util.Base64
import com.mobilerun.portal.core.StateRepository
import com.mobilerun.portal.input.MobilerunKeyboardIME
import com.mobilerun.portal.input.TextInputResult
import com.mobilerun.portal.keepalive.KeepAliveController
import com.mobilerun.portal.keepalive.KeepAliveStartupException
import com.mobilerun.portal.model.PhoneState
import com.mobilerun.portal.service.CaptureSizing
import com.mobilerun.portal.service.MobilerunAccessibilityService
import com.mobilerun.portal.service.ReverseConnectionService
import com.mobilerun.portal.service.ScreenCaptureService
import com.mobilerun.portal.streaming.WebRtcManager
import com.mobilerun.portal.ui.ScreenCaptureActivity
import io.mockk.Runs
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.spyk
import io.mockk.just
import io.mockk.unmockkAll
import io.mockk.verify
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun treeReads_returnErrorWhenServiceConnectedButNoActiveRoot() {
        // Regression (#17 freeze): the a11y service is connected
        // (hasAccessibilityService == true) but there is no active window/root
        // (rootInActiveWindow null and no fallback window) and no elements.
        // getTree/getState used to return Success with an empty list, which an
        // agent misreads as "the screen has nothing on it". They must fail loud
        // with a recovery hint instead.
        val stateRepo = mockk<StateRepository>(relaxed = true)
        every { stateRepo.hasAccessibilityService } returns true
        every { stateRepo.getVisibleElements() } returns emptyList()
        every { stateRepo.hasActiveRoot() } returns false
        val handler = createHandler(stateRepo = stateRepo, ime = null)

        val tree = handler.getTree()
        val state = handler.getState()
        assertEquals(true, tree is ApiResponse.Error)
        assertEquals(true, state is ApiResponse.Error)
        assertEquals(true, (tree as ApiResponse.Error).message.contains("empty"))
        assertEquals(true, (state as ApiResponse.Error).message.contains("empty"))
    }

    @Test
    fun treeReads_returnEmptySuccessWhenWindowPresentButNoSemanticNodes() {
        // A window/root IS present but exposes no a11y elements — a Flutter,
        // native game, or WebView surface with no semantic children. This is a
        // valid screen, NOT the freeze, so getTree/getState must return Success
        // (with an empty tree), never the recovery error.
        val stateRepo = mockk<StateRepository>(relaxed = true)
        every { stateRepo.hasAccessibilityService } returns true
        every { stateRepo.getVisibleElements() } returns emptyList()
        every { stateRepo.hasActiveRoot() } returns true
        val handler = createHandler(stateRepo = stateRepo, ime = null)

        assertEquals(true, handler.getTree() is ApiResponse.Success)
        assertEquals(true, handler.getState() is ApiResponse.Success)
    }

    @Test
    fun nonAccessibilityReads_stillWorkWhenStateRepoHasNoService() {
        val handler = createHandler(stateRepo = StateRepository(service = null), ime = null)

        assertEquals(ApiResponse.Success("pong"), handler.ping())
        assertEquals(ApiResponse.Success("test-version"), handler.getVersion())
    }

    @Test
    fun getClipboard_succeedsViaSelectedIme() {
        val stateRepo = mockk<StateRepository>(relaxed = true)
        val ime = mockk<MobilerunKeyboardIME>()
        val context = mockk<Context>(relaxed = true)
        every { context.applicationContext } returns context
        val handler = createHandler(stateRepo = stateRepo, ime = ime, context = context)

        mockkObject(MobilerunKeyboardIME.Companion)
        every { MobilerunKeyboardIME.isAvailable() } returns true
        every { MobilerunKeyboardIME.isSelected(context) } returns true
        every { ime.getClipboardText() } returns "hello"

        assertEquals(ApiResponse.Success("hello"), handler.getClipboard())
        verify(exactly = 1) { ime.getClipboardText() }
    }

    @Test
    fun getClipboard_succeedsWithEmptyTextViaSelectedIme() {
        val stateRepo = mockk<StateRepository>(relaxed = true)
        val ime = mockk<MobilerunKeyboardIME>()
        val context = mockk<Context>(relaxed = true)
        every { context.applicationContext } returns context
        val handler = createHandler(stateRepo = stateRepo, ime = ime, context = context)

        mockkObject(MobilerunKeyboardIME.Companion)
        every { MobilerunKeyboardIME.isAvailable() } returns true
        every { MobilerunKeyboardIME.isSelected(context) } returns true
        every { ime.getClipboardText() } returns ""

        assertEquals(ApiResponse.Success(""), handler.getClipboard())
        verify(exactly = 1) { ime.getClipboardText() }
    }

    @Test
    fun getClipboard_errorsWhenImeIsUnavailable() {
        val context = mockk<Context>(relaxed = true)
        every { context.applicationContext } returns context
        val handler = createHandler(
            stateRepo = StateRepository(service = null),
            ime = null,
            context = context,
        )

        mockkObject(MobilerunKeyboardIME.Companion)
        every { MobilerunKeyboardIME.isAvailable() } returns false

        assertEquals(
            ApiResponse.Error("Clipboard read requires Mobilerun Keyboard to be selected"),
            handler.getClipboard(),
        )
    }

    @Test
    fun setClipboard_usesImeWhenAvailable() {
        val stateRepo = mockk<StateRepository>(relaxed = true)
        val ime = mockk<MobilerunKeyboardIME>()
        val context = mockk<Context>(relaxed = true)
        val handler = createHandler(stateRepo = stateRepo, ime = ime, context = context)

        every { ime.setClipboardText("hello") } returns true

        assertEquals(ApiResponse.Success("Clipboard set"), handler.setClipboard("hello"))
        verify(exactly = 1) { ime.setClipboardText("hello") }
        verify(exactly = 0) { context.getSystemService(Context.CLIPBOARD_SERVICE) }
    }

    @Test
    fun setClipboard_fallsBackToAppClipboardManagerWhenImeFails() {
        val stateRepo = mockk<StateRepository>(relaxed = true)
        val ime = mockk<MobilerunKeyboardIME>()
        val context = mockk<Context>(relaxed = true)
        val clipboard = mockk<ClipboardManager>(relaxed = true)
        val clip = mockk<ClipData>()
        val handler = createHandler(stateRepo = stateRepo, ime = ime, context = context)

        every { ime.setClipboardText("hello") } returns false
        every { context.getSystemService(Context.CLIPBOARD_SERVICE) } returns clipboard
        mockkStatic(ClipData::class)
        every { ClipData.newPlainText("text", "hello") } returns clip

        assertEquals(ApiResponse.Success("Clipboard set"), handler.setClipboard("hello"))
        verify(exactly = 1) { ime.setClipboardText("hello") }
        verify(exactly = 1) { clipboard.setPrimaryClip(clip) }
    }

    @Test
    fun keyboardInput_returnsSuccessOnlyAfterImeVerification() {
        val stateRepo = mockk<StateRepository>(relaxed = true)
        val ime = mockk<MobilerunKeyboardIME>()
        val handler = createHandler(stateRepo = stateRepo, ime = ime)
        every { ime.inputB64TextResult("encoded", true) } returns TextInputResult.Verified

        assertEquals(
            ApiResponse.Success("input done via IME (clear=true)"),
            handler.keyboardInput("encoded", clear = true),
        )
        verify(exactly = 0) { stateRepo.inputText(any(), any()) }
    }

    @Test
    fun keyboardInput_fallsBackAfterRejectedImeReplacement() {
        val stateRepo = mockk<StateRepository>(relaxed = true)
        val ime = mockk<MobilerunKeyboardIME>()
        val handler = createHandler(stateRepo = stateRepo, ime = ime)
        every { ime.inputB64TextResult("encoded", true) } returns TextInputResult.Rejected
        mockkStatic(Base64::class)
        every { Base64.decode("encoded", Base64.DEFAULT) } returns "hello".toByteArray()
        every { stateRepo.inputText("hello", true) } returns true

        assertEquals(
            ApiResponse.Success("input done via Accessibility (clear=true)"),
            handler.keyboardInput("encoded", clear = true),
        )
        verify(exactly = 1) { stateRepo.inputText("hello", true) }
    }

    @Test
    fun keyboardInput_doesNotFallbackAfterAcceptedUnverifiedImeReplacement() {
        val stateRepo = mockk<StateRepository>(relaxed = true)
        val ime = mockk<MobilerunKeyboardIME>()
        val handler = createHandler(stateRepo = stateRepo, ime = ime)
        every {
            ime.inputB64TextResult("encoded", true)
        } returns TextInputResult.AcceptedUnverified

        assertEquals(
            ApiResponse.Error(
                "input accepted via IME but could not be verified; fallback skipped",
            ),
            handler.keyboardInput("encoded", clear = true),
        )
        verify(exactly = 0) { stateRepo.inputText(any(), any()) }
    }

    @Test
    fun keyboardInput_doesNotDuplicateAcceptedUnverifiedAppend() {
        val stateRepo = mockk<StateRepository>(relaxed = true)
        val ime = mockk<MobilerunKeyboardIME>()
        val handler = createHandler(stateRepo = stateRepo, ime = ime)
        every {
            ime.inputB64TextResult("encoded", false)
        } returns TextInputResult.AcceptedUnverified

        assertEquals(
            ApiResponse.Error(
                "input accepted via IME but could not be verified; fallback skipped",
            ),
            handler.keyboardInput("encoded", clear = false),
        )
        verify(exactly = 0) { stateRepo.inputText(any(), any()) }
    }

    @Test
    fun keyboardInput_doesNotFallbackAfterInputSessionChanges() {
        val stateRepo = mockk<StateRepository>(relaxed = true)
        val ime = mockk<MobilerunKeyboardIME>()
        val handler = createHandler(stateRepo = stateRepo, ime = ime)
        every {
            ime.inputB64TextResult("encoded", true)
        } returns TextInputResult.InputSessionChanged

        assertEquals(
            ApiResponse.Error("input session changed during IME input; fallback skipped"),
            handler.keyboardInput("encoded", clear = true),
        )
        verify(exactly = 0) { stateRepo.inputText(any(), any()) }
    }

    @Test
    fun keyboardInput_doesNotFallbackWhenCommitOutcomeIsUnknown() {
        val stateRepo = mockk<StateRepository>(relaxed = true)
        val ime = mockk<MobilerunKeyboardIME>()
        val handler = createHandler(stateRepo = stateRepo, ime = ime)
        every {
            ime.inputB64TextResult("encoded", false)
        } returns TextInputResult.CommitOutcomeUnknown

        assertEquals(
            ApiResponse.Error("IME commit outcome unknown; fallback skipped"),
            handler.keyboardInput("encoded", clear = false),
        )
        verify(exactly = 0) { stateRepo.inputText(any(), any()) }
    }

    @Test
    fun keyboardClear_returnsSuccessOnlyAfterImeVerification() {
        val stateRepo = mockk<StateRepository>(relaxed = true)
        val ime = mockk<MobilerunKeyboardIME>()
        val handler = createHandler(stateRepo = stateRepo, ime = ime)
        every { ime.hasInputConnection() } returns true
        every { ime.clearTextResult() } returns TextInputResult.Verified

        assertEquals(ApiResponse.Success("Text cleared via IME"), handler.keyboardClear())
        verify(exactly = 0) { stateRepo.inputText(any(), any()) }
    }

    @Test
    fun keyboardClear_fallsBackAfterDefiniteImeRejection() {
        val stateRepo = mockk<StateRepository>(relaxed = true)
        val ime = mockk<MobilerunKeyboardIME>()
        val handler = createHandler(stateRepo = stateRepo, ime = ime)
        every { ime.hasInputConnection() } returns true
        every { ime.clearTextResult() } returns TextInputResult.Rejected
        every { stateRepo.inputText("", true) } returns true

        assertEquals(
            ApiResponse.Success("Text cleared via Accessibility"),
            handler.keyboardClear(),
        )
        verify(exactly = 1) { stateRepo.inputText("", true) }
    }

    @Test
    fun keyboardClear_doesNotFallbackAfterAcceptedUnverifiedImeClear() {
        val stateRepo = mockk<StateRepository>(relaxed = true)
        val ime = mockk<MobilerunKeyboardIME>()
        val handler = createHandler(stateRepo = stateRepo, ime = ime)
        every { ime.hasInputConnection() } returns true
        every { ime.clearTextResult() } returns TextInputResult.AcceptedUnverified

        assertEquals(
            ApiResponse.Error(
                "clear accepted via IME but could not be verified; fallback skipped",
            ),
            handler.keyboardClear(),
        )
        verify(exactly = 0) { stateRepo.inputText(any(), any()) }
    }

    @Test
    fun keyboardClear_doesNotFallbackAfterInputSessionChanges() {
        val stateRepo = mockk<StateRepository>(relaxed = true)
        val ime = mockk<MobilerunKeyboardIME>()
        val handler = createHandler(stateRepo = stateRepo, ime = ime)
        every { ime.hasInputConnection() } returns true
        every { ime.clearTextResult() } returns TextInputResult.InputSessionChanged

        assertEquals(
            ApiResponse.Error("input session changed during IME clear; fallback skipped"),
            handler.keyboardClear(),
        )
        verify(exactly = 0) { stateRepo.inputText(any(), any()) }
    }

    @Test
    fun keyboardClear_doesNotFallbackWhenCommitOutcomeIsUnknown() {
        val stateRepo = mockk<StateRepository>(relaxed = true)
        val ime = mockk<MobilerunKeyboardIME>()
        val handler = createHandler(stateRepo = stateRepo, ime = ime)
        every { ime.hasInputConnection() } returns true
        every { ime.clearTextResult() } returns TextInputResult.CommitOutcomeUnknown

        assertEquals(
            ApiResponse.Error("IME clear commit outcome unknown; fallback skipped"),
            handler.keyboardClear(),
        )
        verify(exactly = 0) { stateRepo.inputText(any(), any()) }
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
    fun openDeepLink_requiresAccessibility() {
        val context = mockk<Context>(relaxed = true)
        val handler =
            createHandler(
                stateRepo = StateRepository(service = null),
                ime = null,
                context = context,
            )

        assertEquals(
            ApiResponse.Error("App launch requires Accessibility service"),
            handler.openDeepLink(0, null, null, "example://private-token"),
        )

        verify(exactly = 0) { context.startActivity(any<Intent>(), any<Bundle>()) }
    }

    @Test
    fun openDeepLink_rejectsBlankLinkAndNegativeDisplayBeforeLaunch() {
        val stateRepo = mockk<StateRepository>(relaxed = true)
        val context = mockk<Context>(relaxed = true)
        every { stateRepo.hasAccessibilityService } returns true
        val handler = createHandler(stateRepo = stateRepo, ime = null, context = context)

        assertEquals(
            ApiResponse.Error("Missing required param: 'deepLink'"),
            handler.openDeepLink(0, null, null, "  "),
        )
        assertEquals(
            ApiResponse.Error("Missing required param: 'deepLink'"),
            handler.openDeepLink(0, null, null, "null"),
        )
        assertEquals(
            ApiResponse.Error("Invalid displayId: must be >= 0"),
            handler.openDeepLink(-1, null, null, "example://path"),
        )
        verify(exactly = 0) { context.startActivity(any<Intent>(), any<Bundle>()) }
    }

    @Test
    fun openDeepLink_defaultsToViewAndForwardsDataFlagsAndDisplay() {
        val stateRepo = mockk<StateRepository>(relaxed = true)
        val context = mockk<Context>(relaxed = true)
        every { stateRepo.hasAccessibilityService } returns true
        val mocks = prepareDeepLinkLaunchMocks("example://path", displayId = 0)
        val handler = createHandler(stateRepo = stateRepo, ime = null, context = context)

        assertEquals(
            ApiResponse.Success("Deep link opened"),
            handler.openDeepLink(0, null, null, "example://path"),
        )

        verify(exactly = 1) { anyConstructed<Intent>().setAction(Intent.ACTION_VIEW) }
        verify(exactly = 1) { anyConstructed<Intent>().setData(mocks.uri) }
        verify(exactly = 0) { anyConstructed<Intent>().setPackage(any()) }
        verify(exactly = 1) {
            anyConstructed<Intent>().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        verify(exactly = 1) { mocks.options.setLaunchDisplayId(0) }
        verify(exactly = 1) { context.startActivity(any<Intent>(), mocks.bundle) }
    }

    @Test
    fun openDeepLink_forwardsCustomActionPackageAndDisplay() {
        val stateRepo = mockk<StateRepository>(relaxed = true)
        val context = mockk<Context>(relaxed = true)
        every { stateRepo.hasAccessibilityService } returns true
        val mocks = prepareDeepLinkLaunchMocks("example://path", displayId = 4)
        val handler = createHandler(stateRepo = stateRepo, ime = null, context = context)

        assertEquals(
            ApiResponse.Success("Deep link opened"),
            handler.openDeepLink(4, "com.example", "com.example.OPEN", "example://path"),
        )

        verify(exactly = 1) { anyConstructed<Intent>().setAction("com.example.OPEN") }
        verify(exactly = 1) { anyConstructed<Intent>().setData(mocks.uri) }
        verify(exactly = 1) { anyConstructed<Intent>().setPackage("com.example") }
        verify(exactly = 1) {
            anyConstructed<Intent>().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        verify(exactly = 1) { mocks.options.setLaunchDisplayId(4) }
        verify(exactly = 1) { context.startActivity(any<Intent>(), mocks.bundle) }
    }

    @Test
    fun openDeepLink_returnsStableErrorWhenNoHandlerExists() {
        assertDeepLinkStartFailure(
            ActivityNotFoundException("secret://must-not-leak"),
            "No activity found to handle deep link",
        )
    }

    @Test
    fun openDeepLink_returnsStableErrorWhenLaunchIsNotPermitted() {
        assertDeepLinkStartFailure(
            SecurityException("secret://must-not-leak"),
            "Deep link launch not permitted",
        )
    }

    @Test
    fun openDeepLink_returnsStableErrorForInvalidLaunch() {
        assertDeepLinkStartFailure(
            IllegalArgumentException("secret://must-not-leak"),
            "Invalid deep link launch",
        )
    }

    @Test
    fun openDeepLink_returnsStableErrorForUnexpectedFailure() {
        assertDeepLinkStartFailure(
            IllegalStateException("secret://must-not-leak"),
            "Failed to open deep link",
        )
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
    fun connectWebRtc_reusesActiveCaptureWithAutoSizeWhenDimensionsAreOmitted() {
        val stateRepo = mockk<StateRepository>(relaxed = true)
        val context = mockk<Context>(relaxed = true)
        every { context.applicationContext } returns context
        val handler = createHandler(stateRepo = stateRepo, ime = null, context = context)
        val manager = mockk<WebRtcManager>(relaxed = true)
        val reverseService = mockk<ReverseConnectionService>(relaxed = true)

        mockkObject(WebRtcManager.Companion)
        mockkObject(ReverseConnectionService.Companion)
        mockkObject(CaptureSizing)
        every { WebRtcManager.getInstance(context) } returns manager
        every { ReverseConnectionService.getInstance() } returns reverseService
        every { CaptureSizing.deriveAutoCaptureSize(context) } returns Pair(572, 1280)
        every { manager.isCaptureActive() } returns true
        every {
            manager.startStreamWithExistingCapture(572, 1280, 30, "session-1", true)
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
        verify(exactly = 1) { CaptureSizing.deriveAutoCaptureSize(context) }
        verify(exactly = 1) {
            manager.startStreamWithExistingCapture(572, 1280, 30, "session-1", true)
        }
    }

    @Test
    fun startStream_reusesActiveCaptureWithAutoSizeAndBindsReverseService() {
        val stateRepo = mockk<StateRepository>(relaxed = true)
        val context = mockk<Context>(relaxed = true)
        every { context.applicationContext } returns context
        val handler = createHandler(stateRepo = stateRepo, ime = null, context = context)
        val manager = mockk<WebRtcManager>(relaxed = true)
        val reverseService = mockk<ReverseConnectionService>(relaxed = true)

        mockkObject(WebRtcManager.Companion)
        mockkObject(ReverseConnectionService.Companion)
        mockkObject(CaptureSizing)
        every { WebRtcManager.getInstance(context) } returns manager
        every { ReverseConnectionService.getInstance() } returns reverseService
        every { CaptureSizing.deriveAutoCaptureSize(context) } returns Pair(572, 1280)
        every { manager.isCaptureActive() } returns true
        every {
            manager.startStreamWithExistingCapture(572, 1280, 30, "session-1", false)
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
        verify(exactly = 1) { CaptureSizing.deriveAutoCaptureSize(context) }
        verify(exactly = 1) {
            manager.startStreamWithExistingCapture(572, 1280, 30, "session-1", false)
        }
    }

    @Test
    fun startStream_preservesLegacyOptIntBehaviorWhenEitherDimensionKeyExists() {
        val stateRepo = mockk<StateRepository>(relaxed = true)
        val context = mockk<Context>(relaxed = true)
        every { context.applicationContext } returns context
        val handler = createHandler(stateRepo = stateRepo, ime = null, context = context)
        val manager = mockk<WebRtcManager>(relaxed = true)

        mockkObject(WebRtcManager.Companion)
        mockkObject(ReverseConnectionService.Companion)
        mockkObject(CaptureSizing)
        every { WebRtcManager.getInstance(context) } returns manager
        every { ReverseConnectionService.getInstance() } returns null
        every { manager.isCaptureActive() } returns true

        data class Case(
            val name: String,
            val params: JSONObject,
            val expectedWidth: Int,
            val expectedHeight: Int,
        )

        val cases =
            listOf(
                Case("width-only", JSONObject().put("width", 900), 900, 1280),
                Case("height-only", JSONObject().put("height", 1600), 720, 1600),
                Case(
                    "null",
                    JSONObject().put("width", JSONObject.NULL).put("height", JSONObject.NULL),
                    720,
                    1280,
                ),
                Case(
                    "invalid",
                    JSONObject().put("width", "bad").put("height", "worse"),
                    720,
                    1280,
                ),
                Case(
                    "boolean",
                    JSONObject().put("width", true).put("height", false),
                    720,
                    1280,
                ),
                Case("zero", JSONObject().put("width", 0).put("height", 0), 144, 256),
                Case(
                    "numeric-string",
                    JSONObject().put("width", "900").put("height", "1600"),
                    900,
                    1600,
                ),
                Case(
                    "explicit",
                    JSONObject().put("width", 800).put("height", 1400),
                    800,
                    1400,
                ),
                Case(
                    "clamped",
                    JSONObject().put("width", 3000).put("height", 5000),
                    1920,
                    3840,
                ),
            )

        cases.forEach { case ->
            val sessionId = "session-${case.name}"
            case.params.put("sessionId", sessionId)

            assertEquals(
                case.name,
                ApiResponse.Success("reusing_capture"),
                handler.startStream(case.params),
            )
            verify(exactly = 1) {
                manager.startStreamWithExistingCapture(
                    case.expectedWidth,
                    case.expectedHeight,
                    30,
                    sessionId,
                    false,
                )
            }
        }

        verify(exactly = 0) { CaptureSizing.deriveAutoCaptureSize(any()) }
    }

    @Test
    fun startStream_newCaptureCarriesAutoSizeFlagOnlyWhenBothKeysAreAbsent() {
        val stateRepo = mockk<StateRepository>(relaxed = true)
        val context = mockk<Context>(relaxed = true)
        every { context.applicationContext } returns context
        val handler = createHandler(stateRepo = stateRepo, ime = null, context = context)
        val manager = mockk<WebRtcManager>(relaxed = true)

        mockkConstructor(Intent::class)
        every { anyConstructed<Intent>().setFlags(any()) } returns mockk(relaxed = true)
        every {
            anyConstructed<Intent>().putExtra(any<String>(), any<Boolean>())
        } returns mockk(relaxed = true)
        every {
            anyConstructed<Intent>().putExtra(any<String>(), any<Int>())
        } returns mockk(relaxed = true)
        every {
            anyConstructed<Intent>().putExtra(any<String>(), any<String>())
        } returns mockk(relaxed = true)
        mockkObject(WebRtcManager.Companion)
        mockkObject(ReverseConnectionService.Companion)
        mockkObject(CaptureSizing)
        every { WebRtcManager.getInstance(context) } returns manager
        every { ReverseConnectionService.getInstance() } returns null
        every { manager.isCaptureActive() } returns false

        assertEquals(
            ApiResponse.Success("prompting_user"),
            handler.startStream(JSONObject().put("sessionId", "session-auto")),
        )
        assertEquals(
            ApiResponse.Success("prompting_user"),
            handler.startStream(
                JSONObject()
                    .put("sessionId", "session-explicit")
                    .put("width", 900)
                    .put("height", 1600),
            ),
        )

        verify(exactly = 1) {
            anyConstructed<Intent>().putExtra(ScreenCaptureActivity.EXTRA_AUTO_CAPTURE_SIZE, true)
        }
        verify(exactly = 1) {
            anyConstructed<Intent>().putExtra(ScreenCaptureActivity.EXTRA_AUTO_CAPTURE_SIZE, false)
        }
        verify(exactly = 1) {
            anyConstructed<Intent>().putExtra(ScreenCaptureService.EXTRA_WIDTH, 720)
        }
        verify(exactly = 1) {
            anyConstructed<Intent>().putExtra(ScreenCaptureService.EXTRA_HEIGHT, 1280)
        }
        verify(exactly = 1) {
            anyConstructed<Intent>().putExtra(ScreenCaptureService.EXTRA_WIDTH, 900)
        }
        verify(exactly = 1) {
            anyConstructed<Intent>().putExtra(ScreenCaptureService.EXTRA_HEIGHT, 1600)
        }
        verify(exactly = 0) { CaptureSizing.deriveAutoCaptureSize(any()) }
        verify(exactly = 2) { context.startActivity(any()) }
    }

    @Test
    fun startStream_capModeActivatesOnlyWhenBothMaxDimsPresentAndPositive() {
        val stateRepo = mockk<StateRepository>(relaxed = true)
        val context = mockk<Context>(relaxed = true)
        every { context.applicationContext } returns context
        val handler = createHandler(stateRepo = stateRepo, ime = null, context = context)
        val manager = mockk<WebRtcManager>(relaxed = true)

        mockkObject(WebRtcManager.Companion)
        mockkObject(ReverseConnectionService.Companion)
        mockkObject(CaptureSizing)
        every { WebRtcManager.getInstance(context) } returns manager
        every { ReverseConnectionService.getInstance() } returns null
        every { manager.isCaptureActive() } returns true
        every { CaptureSizing.deriveAutoCaptureSize(context) } returns Pair(572, 1280)
        every { CaptureSizing.deriveCapCaptureSize(context, 400, 800) } returns Pair(500, 900)
        every { CaptureSizing.deriveCapCaptureSize(context, 410, 810) } returns Pair(510, 910)
        every { CaptureSizing.deriveCapCaptureSize(context, 420, 820) } returns Pair(520, 920)

        data class Case(
            val name: String,
            val params: JSONObject,
            val sessionId: String,
            val expectedWidth: Int,
            val expectedHeight: Int,
        )

        val cases =
            listOf(
                Case(
                    "both positive activates cap mode",
                    JSONObject().put("maxWidth", 400).put("maxHeight", 800),
                    "session-cap-both",
                    500,
                    900,
                ),
                Case(
                    "maxWidth only is a partial pair, falls to auto",
                    JSONObject().put("maxWidth", 400),
                    "session-cap-width-only",
                    572,
                    1280,
                ),
                Case(
                    "maxHeight only is a partial pair, falls to auto",
                    JSONObject().put("maxHeight", 800),
                    "session-cap-height-only",
                    572,
                    1280,
                ),
                Case(
                    "zero maxWidth treated as absent",
                    JSONObject().put("maxWidth", 0).put("maxHeight", 800),
                    "session-cap-zero-width",
                    572,
                    1280,
                ),
                Case(
                    "negative maxHeight treated as absent",
                    JSONObject().put("maxWidth", 400).put("maxHeight", -1),
                    "session-cap-negative-height",
                    572,
                    1280,
                ),
                Case(
                    "non-numeric strings treated as absent",
                    JSONObject().put("maxWidth", "bad").put("maxHeight", "worse"),
                    "session-cap-invalid-strings",
                    572,
                    1280,
                ),
                Case(
                    "numeric strings activate cap mode",
                    JSONObject().put("maxWidth", "410").put("maxHeight", "810"),
                    "session-cap-numeric-strings",
                    510,
                    910,
                ),
                Case(
                    "cap mode takes precedence over explicit width/height",
                    JSONObject()
                        .put("maxWidth", 420)
                        .put("maxHeight", 820)
                        .put("width", 900)
                        .put("height", 1600),
                    "session-cap-precedence",
                    520,
                    920,
                ),
            )

        cases.forEach { case ->
            case.params.put("sessionId", case.sessionId)
            assertEquals(
                case.name,
                ApiResponse.Success("reusing_capture"),
                handler.startStream(case.params),
            )
            verify(exactly = 1) {
                manager.startStreamWithExistingCapture(
                    case.expectedWidth,
                    case.expectedHeight,
                    30,
                    case.sessionId,
                    false,
                )
            }
        }

        verify(exactly = 3) { CaptureSizing.deriveCapCaptureSize(context, any(), any()) }
    }

    @Test
    fun startStream_newCaptureCarriesMaxDimsExtrasWhenCapModeActive() {
        val stateRepo = mockk<StateRepository>(relaxed = true)
        val context = mockk<Context>(relaxed = true)
        every { context.applicationContext } returns context
        val handler = createHandler(stateRepo = stateRepo, ime = null, context = context)
        val manager = mockk<WebRtcManager>(relaxed = true)

        mockkConstructor(Intent::class)
        every { anyConstructed<Intent>().setFlags(any()) } returns mockk(relaxed = true)
        every {
            anyConstructed<Intent>().putExtra(any<String>(), any<Boolean>())
        } returns mockk(relaxed = true)
        every {
            anyConstructed<Intent>().putExtra(any<String>(), any<Int>())
        } returns mockk(relaxed = true)
        every {
            anyConstructed<Intent>().putExtra(any<String>(), any<String>())
        } returns mockk(relaxed = true)
        mockkObject(WebRtcManager.Companion)
        mockkObject(ReverseConnectionService.Companion)
        mockkObject(CaptureSizing)
        every { WebRtcManager.getInstance(context) } returns manager
        every { ReverseConnectionService.getInstance() } returns null
        every { manager.isCaptureActive() } returns false

        assertEquals(
            ApiResponse.Success("prompting_user"),
            handler.startStream(
                JSONObject()
                    .put("sessionId", "session-cap-new")
                    .put("maxWidth", 400)
                    .put("maxHeight", 800),
            ),
        )

        verify(exactly = 1) {
            anyConstructed<Intent>().putExtra(ScreenCaptureActivity.EXTRA_MAX_WIDTH, 400)
        }
        verify(exactly = 1) {
            anyConstructed<Intent>().putExtra(ScreenCaptureActivity.EXTRA_MAX_HEIGHT, 800)
        }
        verify(exactly = 1) {
            anyConstructed<Intent>().putExtra(ScreenCaptureActivity.EXTRA_AUTO_CAPTURE_SIZE, false)
        }
        verify(exactly = 1) {
            anyConstructed<Intent>().putExtra(ScreenCaptureService.EXTRA_WIDTH, 720)
        }
        verify(exactly = 1) {
            anyConstructed<Intent>().putExtra(ScreenCaptureService.EXTRA_HEIGHT, 1280)
        }
        verify(exactly = 0) { CaptureSizing.deriveAutoCaptureSize(any()) }
        verify(exactly = 1) { context.startActivity(any()) }
    }

    @Test
    fun startStream_newCaptureOmitsMaxDimsExtrasWhenCapModeInactive() {
        val stateRepo = mockk<StateRepository>(relaxed = true)
        val context = mockk<Context>(relaxed = true)
        every { context.applicationContext } returns context
        val handler = createHandler(stateRepo = stateRepo, ime = null, context = context)
        val manager = mockk<WebRtcManager>(relaxed = true)

        mockkConstructor(Intent::class)
        every { anyConstructed<Intent>().setFlags(any()) } returns mockk(relaxed = true)
        every {
            anyConstructed<Intent>().putExtra(any<String>(), any<Boolean>())
        } returns mockk(relaxed = true)
        every {
            anyConstructed<Intent>().putExtra(any<String>(), any<Int>())
        } returns mockk(relaxed = true)
        every {
            anyConstructed<Intent>().putExtra(any<String>(), any<String>())
        } returns mockk(relaxed = true)
        mockkObject(WebRtcManager.Companion)
        mockkObject(ReverseConnectionService.Companion)
        every { WebRtcManager.getInstance(context) } returns manager
        every { ReverseConnectionService.getInstance() } returns null
        every { manager.isCaptureActive() } returns false

        assertEquals(
            ApiResponse.Success("prompting_user"),
            handler.startStream(JSONObject().put("sessionId", "session-no-cap")),
        )

        verify(exactly = 0) {
            anyConstructed<Intent>().putExtra(ScreenCaptureActivity.EXTRA_MAX_WIDTH, any<Int>())
        }
        verify(exactly = 0) {
            anyConstructed<Intent>().putExtra(ScreenCaptureActivity.EXTRA_MAX_HEIGHT, any<Int>())
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

    private data class DeepLinkLaunchMocks(
        val uri: Uri,
        val options: ActivityOptions,
        val bundle: Bundle,
    )

    private fun prepareDeepLinkLaunchMocks(
        deepLink: String,
        displayId: Int,
    ): DeepLinkLaunchMocks {
        mockkConstructor(Intent::class)
        mockkStatic(Uri::class)
        mockkStatic(ActivityOptions::class)

        val uri = mockk<Uri>()
        val options = mockk<ActivityOptions>()
        val bundle = mockk<Bundle>()
        every { Uri.parse(deepLink) } returns uri
        every { anyConstructed<Intent>().setAction(any()) } returns mockk(relaxed = true)
        every { anyConstructed<Intent>().setData(uri) } returns mockk(relaxed = true)
        every { anyConstructed<Intent>().setPackage(any()) } returns mockk(relaxed = true)
        every {
            anyConstructed<Intent>().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } returns mockk(relaxed = true)
        every { ActivityOptions.makeBasic() } returns options
        every { options.setLaunchDisplayId(displayId) } returns options
        every { options.toBundle() } returns bundle
        return DeepLinkLaunchMocks(uri, options, bundle)
    }

    private fun assertDeepLinkStartFailure(error: Exception, expectedMessage: String) {
        val stateRepo = mockk<StateRepository>(relaxed = true)
        val context = mockk<Context>(relaxed = true)
        every { stateRepo.hasAccessibilityService } returns true
        val mocks = prepareDeepLinkLaunchMocks("secret://must-not-leak", displayId = 0)
        every { context.startActivity(any<Intent>(), mocks.bundle) } throws error
        val handler = createHandler(stateRepo = stateRepo, ime = null, context = context)

        val response = handler.openDeepLink(0, null, null, "secret://must-not-leak")

        assertEquals(ApiResponse.Error(expectedMessage), response)
        assertFalse((response as ApiResponse.Error).message.contains("secret://"))
    }
}
