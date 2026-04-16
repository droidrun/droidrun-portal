package com.mobilerun.portal.keepalive

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import com.mobilerun.portal.config.ConfigManager
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class KeepAliveControllerTest {
    private lateinit var context: Context
    private lateinit var configManager: ConfigManager
    private lateinit var powerManager: PowerManager
    private lateinit var keyguardManager: KeyguardManager
    private var keepScreenAwakeEnabled = false
    private var keepAliveServiceRunning = false

    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        configManager = mockk(relaxed = true)
        powerManager = mockk(relaxed = true)
        keyguardManager = mockk(relaxed = true)

        every { context.applicationContext } returns context
        every { context.getSystemService(Context.POWER_SERVICE) } returns powerManager
        every { context.getSystemService(Context.KEYGUARD_SERVICE) } returns keyguardManager

        every { configManager.keepScreenAwakeEnabled } answers { keepScreenAwakeEnabled }
        every { configManager.setKeepScreenAwakeEnabledWithNotification(any()) } answers {
            keepScreenAwakeEnabled = firstArg()
        }

        mockkObject(ConfigManager.Companion)
        every { ConfigManager.getInstance(context) } returns configManager

        mockkObject(KeepAliveService.Companion)
        every { KeepAliveService.isRunning() } answers { keepAliveServiceRunning }

        mockkObject(KeepAliveServiceRuntime)
        every { KeepAliveServiceRuntime.start(any()) } just Runs
        every { KeepAliveServiceRuntime.stop(any()) } just Runs
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun noteRecoveryAttempt_updatesLastRecoveryAttemptTimestamp() {
        KeepAliveController.noteRecoveryAttempt(context, atMs = 123L)

        verify(exactly = 1) { configManager.keepAliveLastRecoveryAttemptAtMs = 123L }
    }

    @Test
    fun markRecoverySuccess_clearsRetryThrottleAndResetsFailureTracking() {
        KeepAliveController.markRecoverySuccess(context, atMs = 456L)

        verify(exactly = 1) { configManager.keepAliveLastRecoveryAtMs = 456L }
        verify(exactly = 1) { configManager.keepAliveLastRecoveryAttemptAtMs = 0L }
        verify(exactly = 1) { configManager.keepAliveConsecutiveRecoveryFailures = 0 }
        verify(exactly = 1) { configManager.keepAliveDegradedReason = null }
    }

    @Test
    fun markRecoveryFailure_preservesRetryThrottleAndIncrementsFailureTracking() {
        every { configManager.keepAliveConsecutiveRecoveryFailures } returns 2

        KeepAliveController.markRecoveryFailure(context, reason = "wake_lock_failed", atMs = 789L)

        verify(exactly = 1) { configManager.keepAliveLastRecoveryAtMs = 789L }
        verify(exactly = 1) { configManager.keepAliveLastRecoveryAttemptAtMs = 789L }
        verify(exactly = 1) { configManager.keepAliveConsecutiveRecoveryFailures = 3 }
        verify(exactly = 1) { configManager.keepAliveDegradedReason = "wake_lock_failed" }
    }

    @Test
    fun setEnabled_true_startsRuntime() {
        KeepAliveController.setEnabled(context, true)

        verify(exactly = 1) { configManager.setKeepScreenAwakeEnabledWithNotification(true) }
        verify(exactly = 1) { KeepAliveServiceRuntime.start(context) }
        verify(exactly = 0) { configManager.clearKeepAliveRuntimeState() }
        verify(exactly = 0) { KeepAliveServiceRuntime.stop(any()) }
    }

    @Test
    fun enable_rollsBackPersistedFlagWhenStartupFails() {
        every { KeepAliveServiceRuntime.start(any()) } throws
            KeepAliveStartupException("foreground_service_start_not_allowed")

        try {
            KeepAliveController.enable(context)
            fail("Expected KeepAliveStartupException")
        } catch (e: KeepAliveStartupException) {
            assertEquals("foreground_service_start_not_allowed", e.reason)
        }

        assertFalse(keepScreenAwakeEnabled)
        verify(exactly = 1) { configManager.setKeepScreenAwakeEnabledWithNotification(true) }
        verify(exactly = 1) { configManager.setKeepScreenAwakeEnabledWithNotification(false) }
        verify(exactly = 1) { configManager.clearKeepAliveRuntimeState() }
        verify(exactly = 1) { KeepAliveServiceRuntime.stop(context) }
    }

    @Test
    fun setEnabled_false_clearsRuntimeStateAndStopsServiceWithoutStart() {
        KeepAliveController.setEnabled(context, false)

        verify(exactly = 1) { configManager.setKeepScreenAwakeEnabledWithNotification(false) }
        verify(exactly = 1) { configManager.clearKeepAliveRuntimeState() }
        verify(exactly = 0) { KeepAliveServiceRuntime.start(any()) }
        verify(exactly = 1) { KeepAliveServiceRuntime.stop(context) }
    }

    @Test
    fun reconcile_startsRuntimeWhenEnabled() {
        keepScreenAwakeEnabled = true

        KeepAliveController.reconcile(context)

        verify(exactly = 1) { KeepAliveServiceRuntime.start(context) }
        verify(exactly = 0) { KeepAliveServiceRuntime.stop(any()) }
    }

    @Test
    fun reconcile_stopsRuntimeWhenDisabled() {
        keepScreenAwakeEnabled = false

        KeepAliveController.reconcile(context)

        verify(exactly = 0) { KeepAliveServiceRuntime.start(any()) }
        verify(exactly = 1) { KeepAliveServiceRuntime.stop(context) }
    }

    @Test
    fun reconcileBestEffort_doesNotThrowOrMutateStateWhenStartupIsDeferred() {
        keepScreenAwakeEnabled = true
        every { KeepAliveServiceRuntime.start(any()) } throws
            KeepAliveStartupException("foreground_service_start_not_allowed")

        val result = KeepAliveController.reconcileBestEffort(context)

        assertEquals("foreground_service_start_not_allowed", result.deferredReason)
        assertTrue(keepScreenAwakeEnabled)
        verify(exactly = 1) { KeepAliveServiceRuntime.start(context) }
        verify(exactly = 0) { configManager.clearKeepAliveRuntimeState() }
        verify(exactly = 0) { configManager.setKeepScreenAwakeEnabledWithNotification(false) }
        verify(exactly = 0) { KeepAliveServiceRuntime.stop(any()) }
    }

    @Test
    fun retryStartupIfEnabledAndInactive_startsRuntimeWhenEnabledAndServiceStopped() {
        keepScreenAwakeEnabled = true
        keepAliveServiceRunning = false

        val deferredReason = KeepAliveController.retryStartupIfEnabledAndInactive(context)

        assertEquals(null, deferredReason)
        verify(exactly = 1) { KeepAliveServiceRuntime.start(context) }
        verify(exactly = 0) { configManager.clearKeepAliveRuntimeState() }
        verify(exactly = 0) { KeepAliveServiceRuntime.stop(any()) }
    }

    @Test
    fun retryStartupIfEnabledAndInactive_isNoOpWhenDisabled() {
        keepScreenAwakeEnabled = false

        val deferredReason = KeepAliveController.retryStartupIfEnabledAndInactive(context)

        assertEquals(null, deferredReason)
        verify(exactly = 0) { KeepAliveServiceRuntime.start(any()) }
        verify(exactly = 0) { configManager.clearKeepAliveRuntimeState() }
        verify(exactly = 0) { KeepAliveServiceRuntime.stop(any()) }
    }

    @Test
    fun retryStartupIfEnabledAndInactive_isNoOpWhenServiceAlreadyRunning() {
        keepScreenAwakeEnabled = true
        keepAliveServiceRunning = true

        val deferredReason = KeepAliveController.retryStartupIfEnabledAndInactive(context)

        assertEquals(null, deferredReason)
        verify(exactly = 0) { KeepAliveServiceRuntime.start(any()) }
        verify(exactly = 0) { configManager.clearKeepAliveRuntimeState() }
        verify(exactly = 0) { KeepAliveServiceRuntime.stop(any()) }
    }

    @Test
    fun retryStartupIfEnabledAndInactive_returnsDeferredReasonWithoutMutatingStateWhenStartupFails() {
        keepScreenAwakeEnabled = true
        every { KeepAliveServiceRuntime.start(any()) } throws
            KeepAliveStartupException("foreground_service_start_not_allowed")

        val deferredReason = KeepAliveController.retryStartupIfEnabledAndInactive(context)

        assertEquals("foreground_service_start_not_allowed", deferredReason)
        assertTrue(keepScreenAwakeEnabled)
        verify(exactly = 1) { KeepAliveServiceRuntime.start(context) }
        verify(exactly = 0) { configManager.clearKeepAliveRuntimeState() }
        verify(exactly = 0) { configManager.setKeepScreenAwakeEnabledWithNotification(false) }
        verify(exactly = 0) { KeepAliveServiceRuntime.stop(any()) }
    }

    @Test
    fun setDegradedReason_updatesConfig() {
        KeepAliveController.setDegradedReason(context, "recovery_throttled")

        verify(exactly = 1) { configManager.keepAliveDegradedReason = "recovery_throttled" }
    }

    @Test
    fun getStatus_readsLiveRuntimeState() {
        keepScreenAwakeEnabled = true
        every { KeepAliveService.isRunning() } returns true
        every { powerManager.isInteractive } returns false
        every { keyguardManager.isDeviceLocked } returns true
        every { configManager.keepAliveLastRecoveryAtMs } returns 999L
        every { configManager.keepAliveConsecutiveRecoveryFailures } returns 2
        every { configManager.keepAliveDegradedReason } returns "keyguard_dismiss_failed"

        val status = KeepAliveController.getStatus(context)

        assertTrue(status.enabled)
        assertTrue(status.serviceActive)
        assertFalse(status.interactive)
        assertTrue(status.deviceLocked)
        assertEquals(999L, status.lastRecoveryAtMs)
        assertEquals(2, status.consecutiveRecoveryFailures)
        assertEquals("keyguard_dismiss_failed", status.degradedReason)
    }

    @Test
    fun getMutationResultStatus_overridesTargetStateWhilePreservingLiveFields() {
        keepScreenAwakeEnabled = false
        every { KeepAliveService.isRunning() } returns false
        every { powerManager.isInteractive } returns false
        every { keyguardManager.isDeviceLocked } returns true
        every { configManager.keepAliveLastRecoveryAtMs } returns 1234L
        every { configManager.keepAliveConsecutiveRecoveryFailures } returns 5
        every { configManager.keepAliveDegradedReason } returns "recovery_throttled"

        val status = KeepAliveController.getMutationResultStatus(context, requestedEnabled = true)

        assertTrue(status.enabled)
        assertTrue(status.serviceActive)
        assertFalse(status.interactive)
        assertTrue(status.deviceLocked)
        assertEquals(1234L, status.lastRecoveryAtMs)
        assertEquals(5, status.consecutiveRecoveryFailures)
        assertEquals("recovery_throttled", status.degradedReason)
    }
}
