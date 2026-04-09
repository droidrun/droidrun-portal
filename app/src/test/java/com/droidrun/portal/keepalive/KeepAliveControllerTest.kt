package com.droidrun.portal.keepalive

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import com.droidrun.portal.config.ConfigManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class KeepAliveControllerTest {
    private lateinit var context: Context
    private lateinit var configManager: ConfigManager
    private lateinit var powerManager: PowerManager
    private lateinit var keyguardManager: KeyguardManager
    private var keepScreenAwakeEnabled = false

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
        every { KeepAliveService.isRunning() } returns false
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun noteRecoveryAttempt_updatesLastRecoveryTimestamp() {
        KeepAliveController.noteRecoveryAttempt(context, atMs = 123L)

        verify(exactly = 1) { configManager.keepAliveLastRecoveryAtMs = 123L }
    }

    @Test
    fun markRecoverySuccess_resetsFailureTracking() {
        KeepAliveController.markRecoverySuccess(context, atMs = 456L)

        verify(exactly = 1) { configManager.keepAliveLastRecoveryAtMs = 456L }
        verify(exactly = 1) { configManager.keepAliveConsecutiveRecoveryFailures = 0 }
        verify(exactly = 1) { configManager.keepAliveDegradedReason = null }
    }

    @Test
    fun markRecoveryFailure_incrementsFailureTracking() {
        every { configManager.keepAliveConsecutiveRecoveryFailures } returns 2

        KeepAliveController.markRecoveryFailure(context, reason = "wake_lock_failed", atMs = 789L)

        verify(exactly = 1) { configManager.keepAliveLastRecoveryAtMs = 789L }
        verify(exactly = 1) { configManager.keepAliveConsecutiveRecoveryFailures = 3 }
        verify(exactly = 1) { configManager.keepAliveDegradedReason = "wake_lock_failed" }
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
}
