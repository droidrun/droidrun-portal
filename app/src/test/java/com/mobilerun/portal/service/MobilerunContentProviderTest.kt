package com.mobilerun.portal.service

import android.content.ContentValues
import android.content.Context
import android.util.Log
import com.mobilerun.portal.api.ApiResponse
import com.mobilerun.portal.config.ConfigManager
import com.mobilerun.portal.config.ReverseJoinHttp402Snapshot
import com.mobilerun.portal.keepalive.KeepAliveController
import com.mobilerun.portal.keepalive.KeepAliveStartupException
import com.mobilerun.portal.state.ConnectionState
import com.mobilerun.portal.taskprompt.PortalActiveTaskRecord
import com.mobilerun.portal.taskprompt.PortalTaskLaunchCoordinator
import com.mobilerun.portal.taskprompt.PortalTaskSettings
import com.mobilerun.portal.taskprompt.PortalTaskTracking
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import io.mockk.verifySequence
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class MobilerunContentProviderTest {
    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun handleKeepScreenAwakeInsert_returnsSuccessUriWhenEnableSucceeds() {
        val context = mockk<Context>(relaxed = true)

        mockkObject(KeepAliveController)
        every { KeepAliveController.setEnabled(context, true) } just Runs

        val result = handleKeepScreenAwakeInsert(context, true)

        assertEquals(ApiResponse.Success("Keep screen awake set to true"), result)
        verify(exactly = 1) { KeepAliveController.setEnabled(context, true) }
    }

    @Test
    fun handleKeepScreenAwakeInsert_returnsErrorUriWhenStartupFails() {
        val context = mockk<Context>(relaxed = true)

        mockkObject(KeepAliveController)
        every { KeepAliveController.setEnabled(context, true) } throws
            KeepAliveStartupException("foreground_service_start_not_allowed")

        val result = handleKeepScreenAwakeInsert(context, true)

        assertEquals(
            ApiResponse.Error("foreground_service_start_not_allowed"),
            result,
        )
        verify(exactly = 1) { KeepAliveController.setEnabled(context, true) }
    }

    @Test
    fun handleNoA11yModeInsert_rejectsEnableWhenAccessibilityServiceIsRunning() {
        val context = mockk<Context>(relaxed = true)
        val configManager = mockk<ConfigManager>(relaxed = true)
        var startCalled = false

        val result = handleNoA11yModeInsert(
            providerContext = context,
            configManager = configManager,
            enabled = true,
            accessibilityServiceAvailable = true,
            startPortalService = { startCalled = true },
        )

        assertEquals(ApiResponse.Error("Disable AccessibilityService first"), result)
        assertFalse(startCalled)
        verify(exactly = 0) { configManager.noA11yMode = true }
        verify(exactly = 0) { configManager.noA11yMode = false }
    }

    @Test
    fun handleNoA11yModeInsert_enablesAndStartsPortalServiceWhenAccessibilityIsAbsent() {
        val context = mockk<Context>(relaxed = true)
        val configManager = mockk<ConfigManager>(relaxed = true)
        var startCalled = false

        val result = handleNoA11yModeInsert(
            providerContext = context,
            configManager = configManager,
            enabled = true,
            accessibilityServiceAvailable = false,
            portalServiceRunning = false,
            startPortalService = { startCalled = true },
        )

        assertEquals(ApiResponse.Success("no_a11y_mode=true"), result)
        assertTrue(startCalled)
        verify(exactly = 1) { configManager.noA11yMode = true }
    }

    @Test
    fun handleNoA11yModeInsert_disablesAndStopsPortalService() {
        val context = mockk<Context>(relaxed = true)
        val configManager = mockk<ConfigManager>(relaxed = true)
        var stopCalled = false

        val result = handleNoA11yModeInsert(
            providerContext = context,
            configManager = configManager,
            enabled = false,
            stopPortalService = { stopCalled = true },
        )

        assertEquals(ApiResponse.Success("no_a11y_mode=false"), result)
        assertTrue(stopCalled)
        verify(exactly = 1) { configManager.noA11yMode = false }
    }

    @Test
    fun ensurePortalServiceIfNoA11y_startsServiceWhenModeIsPersistedAndServiceIsAbsent() {
        val context = mockk<Context>(relaxed = true)
        val configManager = mockk<ConfigManager>()
        var startCalled = false

        every { configManager.noA11yMode } returns true

        ensurePortalServiceIfNoA11y(
            providerContext = context,
            configManager = configManager,
            portalServiceRunning = false,
            startPortalService = { startCalled = true },
        )

        assertTrue(startCalled)
    }

    @Test
    fun ensurePortalServiceIfNoA11y_doesNotStartServiceWhenModeIsDisabled() {
        val context = mockk<Context>(relaxed = true)
        val configManager = mockk<ConfigManager>()
        var startCalled = false

        every { configManager.noA11yMode } returns false

        ensurePortalServiceIfNoA11y(
            providerContext = context,
            configManager = configManager,
            portalServiceRunning = false,
            startPortalService = { startCalled = true },
        )

        assertFalse(startCalled)
    }

    @Test
    fun ensureLocalServerHostAvailableForEnable_allowsAccessibilityServiceHost() {
        val context = mockk<Context>(relaxed = true)
        val configManager = mockk<ConfigManager>()
        var startCalled = false

        val result = ensureLocalServerHostAvailableForEnable(
            providerContext = context,
            configManager = configManager,
            accessibilityServiceAvailable = true,
            portalServiceRunning = false,
            startPortalService = { startCalled = true },
        )

        assertEquals(null, result)
        assertFalse(startCalled)
    }

    @Test
    fun ensureLocalServerHostAvailableForEnable_rejectsWhenNoHostModeIsActive() {
        val context = mockk<Context>(relaxed = true)
        val configManager = mockk<ConfigManager>()

        every { configManager.noA11yMode } returns false

        val result = ensureLocalServerHostAvailableForEnable(
            providerContext = context,
            configManager = configManager,
            accessibilityServiceAvailable = false,
            portalServiceRunning = false,
        )

        assertEquals(
            ApiResponse.Error("AccessibilityService or no-a11y mode required to enable local servers"),
            result,
        )
    }

    @Test
    fun ensureLocalServerHostAvailableForEnable_startsPortalServiceWhenNoA11yModeIsActive() {
        val context = mockk<Context>(relaxed = true)
        val configManager = mockk<ConfigManager>()
        var startCalled = false

        every { configManager.noA11yMode } returns true

        val result = ensureLocalServerHostAvailableForEnable(
            providerContext = context,
            configManager = configManager,
            accessibilityServiceAvailable = false,
            portalServiceRunning = false,
            startPortalService = { startCalled = true },
        )

        assertEquals(null, result)
        assertTrue(startCalled)
    }

    @Test
    fun ensureLocalServerHostAvailableForEnable_returnsErrorWhenPortalServiceStartFails() {
        val context = mockk<Context>(relaxed = true)
        val configManager = mockk<ConfigManager>()

        every { configManager.noA11yMode } returns true

        val result = ensureLocalServerHostAvailableForEnable(
            providerContext = context,
            configManager = configManager,
            accessibilityServiceAvailable = false,
            portalServiceRunning = false,
            startPortalService = { throw RuntimeException("foreground_service_start_not_allowed") },
        )

        assertEquals(ApiResponse.Error("foreground_service_start_not_allowed"), result)
    }

    @Test
    fun handleSocketServerToggleInsert_updatesConfigWhenHostIsAvailable() {
        val configManager = mockk<ConfigManager>(relaxed = true)
        val values = mockk<ContentValues>()
        var ensureCalled = false

        every { values.getAsInteger("port") } returns 9090
        every { values.getAsBoolean("enabled") } returns true
        every { values.containsKey("port") } returns true
        every { configManager.socketServerEnabled } returns false
        every { configManager.socketServerPort } returns 8080

        val result = handleSocketServerToggleInsert(
            configManager = configManager,
            values = values,
            ensureLocalServerHost = {
                ensureCalled = true
                null
            },
        )

        assertEquals(ApiResponse.Success("HTTP server enabled on port 9090"), result)
        assertTrue(ensureCalled)
        verify(exactly = 1) { configManager.socketServerPort = 9090 }
        verify(exactly = 1) { configManager.setSocketServerEnabledWithNotification(true) }
        verify(exactly = 0) { configManager.setSocketServerPortWithNotification(any()) }
    }

    @Test
    fun handleSocketServerToggleInsert_rejectsEnableWhenNoHostIsAvailable() {
        val configManager = mockk<ConfigManager>(relaxed = true)
        val values = mockk<ContentValues>()

        every { values.getAsInteger("port") } returns 9090
        every { values.getAsBoolean("enabled") } returns true
        every { values.containsKey("port") } returns true
        every { configManager.socketServerEnabled } returns false
        every { configManager.socketServerPort } returns 8080

        val result = handleSocketServerToggleInsert(
            configManager = configManager,
            values = values,
            ensureLocalServerHost = {
                ApiResponse.Error("AccessibilityService or no-a11y mode required to enable local servers")
            },
        )

        assertEquals(
            ApiResponse.Error("AccessibilityService or no-a11y mode required to enable local servers"),
            result,
        )
        verify(exactly = 0) { configManager.socketServerPort = 9090 }
        verify(exactly = 0) { configManager.setSocketServerEnabledWithNotification(any()) }
        verify(exactly = 0) { configManager.setSocketServerPortWithNotification(any()) }
    }

    @Test
    fun handleSocketServerToggleInsert_allowsDisableWithoutHost() {
        val configManager = mockk<ConfigManager>(relaxed = true)
        val values = mockk<ContentValues>()
        var ensureCalled = false

        every { values.getAsInteger("port") } returns null
        every { values.getAsBoolean("enabled") } returns false
        every { values.containsKey("port") } returns false
        every { configManager.socketServerEnabled } returns true
        every { configManager.socketServerPort } returns 8080

        val result = handleSocketServerToggleInsert(
            configManager = configManager,
            values = values,
            ensureLocalServerHost = {
                ensureCalled = true
                ApiResponse.Error("should not be called")
            },
        )

        assertEquals(ApiResponse.Success("HTTP server disabled on port 8080"), result)
        assertFalse(ensureCalled)
        verify(exactly = 1) { configManager.setSocketServerEnabledWithNotification(false) }
    }

    @Test
    fun handleWebSocketServerToggleInsert_updatesConfigWhenHostIsAvailable() {
        val configManager = mockk<ConfigManager>(relaxed = true)
        val values = mockk<ContentValues>()
        var ensureCalled = false

        every { values.getAsInteger("port") } returns 9091
        every { values.getAsBoolean("enabled") } returns true
        every { values.containsKey("port") } returns true
        every { configManager.websocketEnabled } returns false
        every { configManager.websocketPort } returns 8081

        val result = handleWebSocketServerToggleInsert(
            configManager = configManager,
            values = values,
            ensureLocalServerHost = {
                ensureCalled = true
                null
            },
        )

        assertEquals(ApiResponse.Success("WebSocket server enabled on port 9091"), result)
        assertTrue(ensureCalled)
        verify(exactly = 1) { configManager.websocketPort = 9091 }
        verify(exactly = 1) { configManager.setWebSocketEnabledWithNotification(true) }
        verify(exactly = 0) { configManager.setWebSocketPortWithNotification(any()) }
    }

    @Test
    fun handleWebSocketServerToggleInsert_rejectsEnableWhenNoHostIsAvailable() {
        val configManager = mockk<ConfigManager>(relaxed = true)
        val values = mockk<ContentValues>()

        every { values.getAsInteger("port") } returns 9091
        every { values.getAsBoolean("enabled") } returns true
        every { values.containsKey("port") } returns true
        every { configManager.websocketEnabled } returns false
        every { configManager.websocketPort } returns 8081

        val result = handleWebSocketServerToggleInsert(
            configManager = configManager,
            values = values,
            ensureLocalServerHost = {
                ApiResponse.Error("AccessibilityService or no-a11y mode required to enable local servers")
            },
        )

        assertEquals(
            ApiResponse.Error("AccessibilityService or no-a11y mode required to enable local servers"),
            result,
        )
        verify(exactly = 0) { configManager.websocketPort = 9091 }
        verify(exactly = 0) { configManager.setWebSocketEnabledWithNotification(any()) }
        verify(exactly = 0) { configManager.setWebSocketPortWithNotification(any()) }
    }

    @Test
    fun handleWebSocketServerToggleInsert_allowsDisableWithoutHost() {
        val configManager = mockk<ConfigManager>(relaxed = true)
        val values = mockk<ContentValues>()
        var ensureCalled = false

        every { values.getAsInteger("port") } returns null
        every { values.getAsBoolean("enabled") } returns false
        every { values.containsKey("port") } returns false
        every { configManager.websocketEnabled } returns true
        every { configManager.websocketPort } returns 8081

        val result = handleWebSocketServerToggleInsert(
            configManager = configManager,
            values = values,
            ensureLocalServerHost = {
                ensureCalled = true
                ApiResponse.Error("should not be called")
            },
        )

        assertEquals(ApiResponse.Success("WebSocket server disabled on port 8081"), result)
        assertFalse(ensureCalled)
        verify(exactly = 1) { configManager.setWebSocketEnabledWithNotification(false) }
    }

    @Test
    fun handleWebSocketServerToggleInsert_isIdempotentWhenAlreadyEnabledOnSamePort() {
        val configManager = mockk<ConfigManager>(relaxed = true)
        val values = mockk<ContentValues>()
        var ensureCalled = false

        every { values.getAsInteger("port") } returns 8081
        every { values.getAsBoolean("enabled") } returns true
        every { values.containsKey("port") } returns true
        every { configManager.websocketEnabled } returns true
        every { configManager.websocketPort } returns 8081

        val result = handleWebSocketServerToggleInsert(
            configManager = configManager,
            values = values,
            ensureLocalServerHost = {
                ensureCalled = true
                null
            },
        )

        assertEquals(ApiResponse.Success("WebSocket server enabled on port 8081"), result)
        assertTrue(ensureCalled)
        verify(exactly = 0) { configManager.setWebSocketPortWithNotification(any()) }
        verify(exactly = 0) { configManager.setWebSocketEnabledWithNotification(any()) }
    }

    @Test
    fun handleCloudConnectInsert_storesApiKeyAndStartsReverseService() {
        val context = mockk<Context>(relaxed = true)
        val configManager = mockk<ConfigManager>(relaxed = true)
        val defaultUrl = "wss://api.mobilerun.ai/v1/providers/personal/join"
        val started = AtomicBoolean(false)
        var startedAction: String? = null

        every { configManager.defaultReverseConnectionUrl } returns defaultUrl

        val result = handleCloudConnectInsert(
            providerContext = context,
            configManager = configManager,
            values = null,
            readStringValue = { _, key ->
                when (key) {
                    "api_key" -> " real-token "
                    else -> null
                }
            },
            startReverseConnectionService = { _, action ->
                started.set(true)
                startedAction = action
            },
        )

        assertEquals(ApiResponse.Success("Cloud connection requested"), result)
        assertTrue(started.get())
        assertEquals(ReverseConnectionService.ACTION_RECONNECT, startedAction)
        verifySequence {
            configManager.defaultReverseConnectionUrl
            configManager.reverseConnectionUrl = defaultUrl
            configManager.reverseConnectionToken = "real-token"
            configManager.forceLoginOnNextConnect = false
            configManager.reverseConnectionEnabled = true
        }
    }

    @Test
    fun handleCloudConnectInsert_acceptsTokenAliasAndBase64Url() {
        val context = mockk<Context>(relaxed = true)
        val configManager = mockk<ConfigManager>(relaxed = true)
        val url = "wss://staging.mobilerun.ai/v1/providers/personal/join"
        val started = AtomicBoolean(false)
        val values = mockk<ContentValues>(relaxed = true)

        mockkStatic(android.util.Base64::class)
        every { values.containsKey("token_base64") } returns true
        every { values.getAsString("token_base64") } returns "encoded-token"
        every { values.containsKey("url_base64") } returns true
        every { values.getAsString("url_base64") } returns "encoded-url"
        every { android.util.Base64.decode("encoded-token", android.util.Base64.DEFAULT) } returns
            " aliased-token ".toByteArray()
        every { android.util.Base64.decode("encoded-url", android.util.Base64.DEFAULT) } returns
            url.toByteArray()

        val result = handleCloudConnectInsert(
            providerContext = context,
            configManager = configManager,
            values = values,
            startReverseConnectionService = { _, _ ->
                started.set(true)
            },
        )

        assertEquals(ApiResponse.Success("Cloud connection requested"), result)
        assertTrue(started.get())
        verifySequence {
            configManager.reverseConnectionUrl = url
            configManager.reverseConnectionToken = "aliased-token"
            configManager.forceLoginOnNextConnect = false
            configManager.reverseConnectionEnabled = true
        }
    }

    @Test
    fun handleCloudConnectInsert_rejectsMissingApiKey() {
        val configManager = mockk<ConfigManager>(relaxed = true)

        val result = handleCloudConnectInsert(
            providerContext = mockk(relaxed = true),
            configManager = configManager,
            values = null,
            readStringValue = { _, _ -> null },
            startReverseConnectionService = { _, _ -> },
        )

        assertEquals(ApiResponse.Error("Missing required value: api_key"), result)
        verify(exactly = 0) { configManager.reverseConnectionToken = any() }
        verify(exactly = 0) { configManager.reverseConnectionEnabled = any() }
    }

    @Test
    fun handleCloudConnectInsert_rejectsUnsupportedUrl() {
        val configManager = mockk<ConfigManager>(relaxed = true)

        val result = handleCloudConnectInsert(
            providerContext = mockk(relaxed = true),
            configManager = configManager,
            values = null,
            readStringValue = { _, key ->
                when (key) {
                    "api_key" -> "real-token"
                    "url" -> "wss://api.mobilerun.ai/ws"
                    else -> null
                }
            },
            startReverseConnectionService = { _, _ -> },
        )

        assertEquals(
            ApiResponse.Error("Cloud connection URL must end in /v1/providers/personal/join"),
            result,
        )
        verify(exactly = 0) { configManager.reverseConnectionToken = any() }
        verify(exactly = 0) { configManager.reverseConnectionEnabled = any() }
    }

    @Test
    fun handleReverseConnectionConfigInsert_returnsErrorWhenReconnectStartThrows() {
        val context = mockk<Context>()
        val values = mockk<ContentValues>()
        val configManager = mockk<ConfigManager>(relaxed = true)

        every { context.applicationContext } returns context
        every { values.getAsBoolean("enabled") } returns true

        val result = handleReverseConnectionConfigInsert(
            providerContext = context,
            configManager = configManager,
            values = values,
            readStringValue = { _, _ -> null },
            startReverseConnectionService = { _, _ ->
                throw IllegalStateException("start failed")
            },
        )

        assertEquals(ApiResponse.Error("Exception: start failed"), result)
    }

    @Test
    fun handleReverseConnectionConfigInsert_enableUsesExplicitReconnectAction() {
        val context = mockk<Context>(relaxed = true)
        val values = mockk<ContentValues>()
        val configManager = mockk<ConfigManager>(relaxed = true)
        var startedAction: String? = null

        every { values.getAsBoolean("enabled") } returns true

        val result = handleReverseConnectionConfigInsert(
            providerContext = context,
            configManager = configManager,
            values = values,
            readStringValue = { _, _ -> null },
            startReverseConnectionService = { _, action -> startedAction = action },
        )

        assertEquals(
            ApiResponse.Success("Updated reverse connection config: enabled=true"),
            result,
        )
        assertEquals(ReverseConnectionService.ACTION_RECONNECT, startedAction)
    }

    @Test
    fun handleReverseConnectionConfigInsert_changedConfigReconnectsWhenEnabled() {
        val context = mockk<Context>(relaxed = true)
        val values = mockk<ContentValues>()
        val configManager = mockk<ConfigManager>(relaxed = true)
        var startedAction: String? = null

        every { values.getAsBoolean("enabled") } returns null
        every { configManager.reverseConnectionUrlOrDefault } returns
            "wss://old.example/v1/providers/personal/join"
        every { configManager.reverseConnectionEnabled } returns true

        val result = handleReverseConnectionConfigInsert(
            providerContext = context,
            configManager = configManager,
            values = values,
            readStringValue = { _, key ->
                if (key == "url") "wss://new.example/v1/providers/personal/join" else null
            },
            startReverseConnectionService = { _, action -> startedAction = action },
        )

        assertTrue(result is ApiResponse.Success)
        assertEquals(ReverseConnectionService.ACTION_RECONNECT, startedAction)
    }

    @Test
    fun handleReverseConnectionConfigInsert_unchangedConfigDoesNotReconnect() {
        val context = mockk<Context>(relaxed = true)
        val values = mockk<ContentValues>()
        val configManager = mockk<ConfigManager>(relaxed = true)
        val defaultUrl = "wss://api.mobilerun.ai/v1/providers/personal/join"
        var startCount = 0

        every { values.getAsBoolean("enabled") } returns null
        every { configManager.defaultReverseConnectionUrl } returns defaultUrl
        every { configManager.reverseConnectionUrlOrDefault } returns defaultUrl
        every { configManager.reverseConnectionToken } returns "token"
        every { configManager.reverseConnectionEnabled } returns true

        val result = handleReverseConnectionConfigInsert(
            providerContext = context,
            configManager = configManager,
            values = values,
            readStringValue = { _, key ->
                when (key) {
                    "url" -> ""
                    "token" -> "token"
                    else -> null
                }
            },
            startReverseConnectionService = { _, _ -> startCount += 1 },
        )

        assertTrue(result is ApiResponse.Success)
        assertEquals(0, startCount)
    }

    @Test
    fun handleReverseConnectionConfigInsert_disableRetainsBlockAndPublishesDisconnected() {
        val context = mockk<Context>(relaxed = true)
        val values = mockk<ContentValues>()
        val configManager = mockk<ConfigManager>(relaxed = true)
        var publishedState: ConnectionState? = null
        var stopCount = 0

        every { values.getAsBoolean("enabled") } returns false
        every { configManager.reverseJoinBlockedByHttp402 } returns true

        val result = handleReverseConnectionConfigInsert(
            providerContext = context,
            configManager = configManager,
            values = values,
            readStringValue = { _, _ -> null },
            stopReverseConnectionService = { _, _ -> stopCount += 1 },
            publishConnectionState = { publishedState = it },
        )

        assertEquals(
            ApiResponse.Success("Updated reverse connection config: enabled=false"),
            result,
        )
        assertEquals(ConnectionState.DISCONNECTED, publishedState)
        assertEquals(1, stopCount)
        verify(exactly = 1) { configManager.markReverseJoinExplicitlyDisconnected() }
        verify(exactly = 0) { configManager.reverseJoinBlockedByHttp402 = false }
    }

    @Test
    fun buildCloudStatusResponse_reportsStateAndRedactsToken() {
        val configManager = mockk<ConfigManager>()
        every { configManager.reverseConnectionToken } returns "super-secret"
        every { configManager.deviceID } returns "device-123"
        every { configManager.reverseConnectionUrlOrDefault } returns
            "wss://api.mobilerun.ai/v1/providers/personal/join"
        every { configManager.activePortalTask } returns null
        every { configManager.reverseJoinHttp402Snapshot() } returns
            ReverseJoinHttp402Snapshot(
                blocked = false,
                explicitlyDisconnected = false,
            )

        val result = buildCloudStatusResponse(
            configManager = configManager,
            connectionStateProvider = { ConnectionState.CONNECTED },
        )

        assertTrue(result is ApiResponse.RawObject)
        val json = (result as ApiResponse.RawObject).json
        assertEquals("CONNECTED", json.getString("connectionState"))
        assertTrue(json.getBoolean("tokenPresent"))
        assertEquals("device-123", json.getString("deviceId"))
        assertFalse(json.toString().contains("super-secret"))
    }

    @Test
    fun buildCloudStatusResponse_reportsPersistedHttp402BlockAsLimitExceeded() {
        val configManager = mockk<ConfigManager>()
        every { configManager.reverseConnectionToken } returns "super-secret"
        every { configManager.deviceID } returns "device-123"
        every { configManager.reverseConnectionUrlOrDefault } returns
            "wss://api.mobilerun.ai/v1/providers/personal/join"
        every { configManager.activePortalTask } returns null
        every { configManager.reverseJoinHttp402Snapshot() } returns
            ReverseJoinHttp402Snapshot(
                blocked = true,
                explicitlyDisconnected = false,
            )

        val result = buildCloudStatusResponse(
            configManager = configManager,
            connectionStateProvider = { ConnectionState.DISCONNECTED },
        )

        assertTrue(result is ApiResponse.RawObject)
        val json = (result as ApiResponse.RawObject).json
        assertEquals("LIMIT_EXCEEDED", json.getString("connectionState"))
    }

    @Test
    fun buildCloudStatusResponse_reportsExplicitDisconnectWhileRetainingHttp402Block() {
        val configManager = mockk<ConfigManager>()
        every { configManager.reverseConnectionToken } returns "super-secret"
        every { configManager.deviceID } returns "device-123"
        every { configManager.reverseConnectionUrlOrDefault } returns
            "wss://api.mobilerun.ai/v1/providers/personal/join"
        every { configManager.activePortalTask } returns null
        every { configManager.reverseJoinHttp402Snapshot() } returns
            ReverseJoinHttp402Snapshot(
                blocked = true,
                explicitlyDisconnected = true,
            )

        val result = buildCloudStatusResponse(
            configManager = configManager,
            connectionStateProvider = { ConnectionState.LIMIT_EXCEEDED },
        )

        assertTrue(result is ApiResponse.RawObject)
        val json = (result as ApiResponse.RawObject).json
        assertEquals("DISCONNECTED", json.getString("connectionState"))
        assertTrue(configManager.reverseJoinHttp402Snapshot().blocked)
    }

    @Test
    fun handleCloudTaskLaunchInsert_returnsTaskIdOnSuccess() {
        val context = mockk<Context>(relaxed = true)
        val configManager = mockk<ConfigManager>()
        val defaultSettings = PortalTaskSettings()
        val record = PortalActiveTaskRecord(
            taskId = "task-123",
            promptPreview = "Open Settings",
            startedAtMs = 1_000L,
            executionTimeoutSec = 100,
            pollDeadlineMs = 101_000L,
        )

        every { configManager.reverseConnectionToken } returns "real-token"
        every { configManager.reverseConnectionUrlOrDefault } returns
            "wss://api.mobilerun.ai/v1/providers/personal/join"
        every { configManager.activePortalTask } returns null
        every { configManager.taskPromptSettings } returns defaultSettings
        every { configManager.taskPromptReturnToPortal } returns false

        val result = handleCloudTaskLaunchInsert(
            providerContext = context,
            configManager = configManager,
            values = null,
            readStringValue = { _, key ->
                when (key) {
                    "prompt" -> "Open Settings"
                    else -> null
                }
            },
            connectionStateProvider = { ConnectionState.CONNECTED },
            taskLaunchInvoker = CloudTaskLaunchInvoker { prompt, settings, metadata, skipBusyCheck, memoryNamespace, onComplete ->
                assertEquals("Open Settings", prompt)
                assertEquals(defaultSettings, settings)
                assertFalse(metadata.returnToPortalOnTerminal)
                assertFalse(skipBusyCheck)
                assertEquals(null, memoryNamespace)
                onComplete(PortalTaskLaunchCoordinator.Result.Success(record))
            },
            timeoutMs = 50L,
        )

        assertTrue(result is ApiResponse.RawObject)
        val json = (result as ApiResponse.RawObject).json
        assertEquals("task-123", json.getString("task_id"))
        assertEquals("created", json.getString("status"))
    }

    @Test
    fun handleCloudTaskLaunchInsert_rejectsMissingKey() {
        val configManager = mockk<ConfigManager>()
        every { configManager.reverseConnectionToken } returns ""

        val result = handleCloudTaskLaunchInsert(
            providerContext = mockk(relaxed = true),
            configManager = configManager,
            values = null,
            readStringValue = { _, _ -> "Open Settings" },
            connectionStateProvider = { ConnectionState.CONNECTED },
            taskLaunchInvoker = CloudTaskLaunchInvoker { _, _, _, _, _, _ -> },
            timeoutMs = 50L,
        )

        assertEquals(ApiResponse.Error("Task launch requires a Mobilerun API key."), result)
    }

    @Test
    fun handleCloudTaskLaunchInsert_rejectsUnsupportedUrl() {
        val configManager = mockk<ConfigManager>()
        every { configManager.reverseConnectionToken } returns "real-token"
        every { configManager.reverseConnectionUrlOrDefault } returns "wss://api.mobilerun.ai/ws"

        val result = handleCloudTaskLaunchInsert(
            providerContext = mockk(relaxed = true),
            configManager = configManager,
            values = null,
            readStringValue = { _, _ -> "Open Settings" },
            connectionStateProvider = { ConnectionState.CONNECTED },
            taskLaunchInvoker = CloudTaskLaunchInvoker { _, _, _, _, _, _ -> },
            timeoutMs = 50L,
        )

        assertEquals(
            ApiResponse.Error("Task launch only supports WebSocket URLs ending in /v1/providers/personal/join."),
            result,
        )
    }

    @Test
    fun handleCloudTaskLaunchInsert_rejectsDisconnectedCloud() {
        val configManager = mockk<ConfigManager>()
        every { configManager.reverseConnectionToken } returns "real-token"
        every { configManager.reverseConnectionUrlOrDefault } returns
            "wss://api.mobilerun.ai/v1/providers/personal/join"

        val result = handleCloudTaskLaunchInsert(
            providerContext = mockk(relaxed = true),
            configManager = configManager,
            values = null,
            readStringValue = { _, _ -> "Open Settings" },
            connectionStateProvider = { ConnectionState.DISCONNECTED },
            taskLaunchInvoker = CloudTaskLaunchInvoker { _, _, _, _, _, _ -> },
            timeoutMs = 50L,
        )

        assertEquals(ApiResponse.Error("Cloud connection is not connected"), result)
    }

    @Test
    fun handleCloudTaskLaunchInsert_rejectsBlankPrompt() {
        val configManager = mockk<ConfigManager>()
        every { configManager.reverseConnectionToken } returns "real-token"
        every { configManager.reverseConnectionUrlOrDefault } returns
            "wss://api.mobilerun.ai/v1/providers/personal/join"

        val result = handleCloudTaskLaunchInsert(
            providerContext = mockk(relaxed = true),
            configManager = configManager,
            values = null,
            readStringValue = { _, _ -> "   " },
            connectionStateProvider = { ConnectionState.CONNECTED },
            taskLaunchInvoker = CloudTaskLaunchInvoker { _, _, _, _, _, _ -> },
            timeoutMs = 50L,
        )

        assertEquals(ApiResponse.Error("Missing required value: prompt"), result)
    }

    @Test
    fun handleCloudTaskLaunchInsert_rejectsActiveBlockingTask() {
        val configManager = mockk<ConfigManager>()
        val values = mockk<ContentValues>()
        val launchCalled = AtomicBoolean(false)
        val activeTask = PortalActiveTaskRecord(
            taskId = "task-active",
            promptPreview = "Running",
            startedAtMs = 1_000L,
            executionTimeoutSec = 100,
            pollDeadlineMs = 101_000L,
            lastStatus = PortalTaskTracking.STATUS_RUNNING,
        )

        every { configManager.reverseConnectionToken } returns "real-token"
        every { configManager.reverseConnectionUrlOrDefault } returns
            "wss://api.mobilerun.ai/v1/providers/personal/join"
        every { configManager.activePortalTask } returns activeTask
        every { values.getAsBoolean("skip_busy_check") } returns true

        val result = handleCloudTaskLaunchInsert(
            providerContext = mockk(relaxed = true),
            configManager = configManager,
            values = values,
            readStringValue = { _, _ -> "Open Settings" },
            connectionStateProvider = { ConnectionState.CONNECTED },
            taskLaunchInvoker = CloudTaskLaunchInvoker { _, _, _, _, _, _ ->
                launchCalled.set(true)
            },
            timeoutMs = 50L,
        )

        assertEquals(ApiResponse.Error("A Mobilerun task is already running"), result)
        assertFalse(launchCalled.get())
    }

    @Test
    fun handleCloudTaskLaunchInsert_returnsTimeoutWhenCloudDoesNotRespond() {
        val configManager = mockk<ConfigManager>()
        every { configManager.reverseConnectionToken } returns "real-token"
        every { configManager.reverseConnectionUrlOrDefault } returns
            "wss://api.mobilerun.ai/v1/providers/personal/join"
        every { configManager.activePortalTask } returns null
        every { configManager.taskPromptSettings } returns PortalTaskSettings()
        every { configManager.taskPromptReturnToPortal } returns false

        val result = handleCloudTaskLaunchInsert(
            providerContext = mockk(relaxed = true),
            configManager = configManager,
            values = null,
            readStringValue = { _, _ -> "Open Settings" },
            connectionStateProvider = { ConnectionState.CONNECTED },
            taskLaunchInvoker = CloudTaskLaunchInvoker { _, _, _, _, _, _ -> },
            timeoutMs = 1L,
        )

        assertEquals(ApiResponse.Error("Timed out waiting for Mobilerun task launch"), result)
    }

    @Test
    fun handleCloudTaskLaunchInsert_returnsCloudError() {
        val configManager = mockk<ConfigManager>()
        every { configManager.reverseConnectionToken } returns "real-token"
        every { configManager.reverseConnectionUrlOrDefault } returns
            "wss://api.mobilerun.ai/v1/providers/personal/join"
        every { configManager.activePortalTask } returns null
        every { configManager.taskPromptSettings } returns PortalTaskSettings()
        every { configManager.taskPromptReturnToPortal } returns false

        val result = handleCloudTaskLaunchInsert(
            providerContext = mockk(relaxed = true),
            configManager = configManager,
            values = null,
            readStringValue = { _, _ -> "Open Settings" },
            connectionStateProvider = { ConnectionState.CONNECTED },
            taskLaunchInvoker = CloudTaskLaunchInvoker { _, _, _, _, _, onComplete ->
                onComplete(PortalTaskLaunchCoordinator.Result.Error("Cloud rejected task"))
            },
            timeoutMs = 50L,
        )

        assertEquals(ApiResponse.Error("Cloud rejected task"), result)
    }
}
