package com.droidrun.portal.keepalive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeepAliveRecoveryActivityStatePolicyTest {
    @Test
    fun resultForResume_doesNotCompleteWhenDeviceIsUnlockedButStillDark() {
        val result =
            KeepAliveRecoveryActivityStatePolicy.resultForResume(
                KeepAliveRecoveryScreenState(
                    interactive = false,
                    deviceLocked = false,
                ),
            )

        assertNull(result)
    }

    @Test
    fun resultForResume_completesWhenDeviceIsInteractiveAndUnlocked() {
        val result =
            KeepAliveRecoveryActivityStatePolicy.resultForResume(
                KeepAliveRecoveryScreenState(
                    interactive = true,
                    deviceLocked = false,
                ),
            )

        requireNotNull(result)
        assertEquals(true, result.success)
        assertEquals(null, result.reason)
    }

    @Test
    fun resultForDismissCallback_waitsWhenDismissIsCancelledAndDeviceIsStillLocked() {
        val result =
            KeepAliveRecoveryActivityStatePolicy.resultForDismissCallback(
                KeepAliveRecoveryScreenState(
                    interactive = true,
                    deviceLocked = true,
                ),
                KeepAliveDismissCallbackState.Failed("dismiss_cancelled"),
            )

        assertNull(result)
    }

    @Test
    fun resultForDismissCallback_waitsWhenDismissSucceedsButScreenIsStillDark() {
        val result =
            KeepAliveRecoveryActivityStatePolicy.resultForDismissCallback(
                KeepAliveRecoveryScreenState(
                    interactive = false,
                    deviceLocked = false,
                ),
                KeepAliveDismissCallbackState.Succeeded,
            )

        assertNull(result)
    }

    @Test
    fun resultForDismissCallback_completesWhenDeviceRecoversAfterDismissFailure() {
        val result =
            KeepAliveRecoveryActivityStatePolicy.resultForDismissCallback(
                KeepAliveRecoveryScreenState(
                    interactive = true,
                    deviceLocked = false,
                ),
                KeepAliveDismissCallbackState.Failed("dismiss_cancelled"),
            )

        requireNotNull(result)
        assertEquals(true, result.success)
        assertEquals(null, result.reason)
    }

    @Test
    fun resultForTimeout_preservesDismissFailureReasonWhenDeviceStaysLocked() {
        val result =
            KeepAliveRecoveryActivityStatePolicy.resultForTimeout(
                KeepAliveRecoveryScreenState(
                    interactive = true,
                    deviceLocked = true,
                ),
                KeepAliveDismissCallbackState.Failed("dismiss_cancelled"),
            )

        assertEquals(false, result.success)
        assertEquals("dismiss_cancelled", result.reason)
    }

    @Test
    fun resultForTimeout_usesLiveStateReasonWhenScreenIsStillDarkWithoutDismissFailure() {
        val result =
            KeepAliveRecoveryActivityStatePolicy.resultForTimeout(
                KeepAliveRecoveryScreenState(
                    interactive = false,
                    deviceLocked = false,
                ),
            )

        assertEquals(false, result.success)
        assertEquals("screen_not_interactive", result.reason)
    }

    @Test
    fun resultForTimeout_usesLiveStateReasonWhenDeviceIsStillLockedWithoutDismissFailure() {
        val result =
            KeepAliveRecoveryActivityStatePolicy.resultForTimeout(
                KeepAliveRecoveryScreenState(
                    interactive = true,
                    deviceLocked = true,
                ),
                KeepAliveDismissCallbackState.None,
            )

        assertEquals(false, result.success)
        assertEquals("device_still_locked", result.reason)
    }
}
