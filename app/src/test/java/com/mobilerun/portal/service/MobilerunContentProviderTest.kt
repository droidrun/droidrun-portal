package com.mobilerun.portal.service

import android.content.ContentValues
import android.content.Context
import android.util.Log
import com.mobilerun.portal.api.ApiResponse
import com.mobilerun.portal.config.ConfigManager
import com.mobilerun.portal.keepalive.KeepAliveController
import com.mobilerun.portal.keepalive.KeepAliveStartupException
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
}
