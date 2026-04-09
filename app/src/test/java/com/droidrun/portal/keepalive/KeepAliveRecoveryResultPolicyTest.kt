package com.droidrun.portal.keepalive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeepAliveRecoveryResultPolicyTest {
    @Test
    fun evaluate_ignoresResultsWhenFeatureIsDisabled() {
        val decision =
            KeepAliveRecoveryResultPolicy.evaluate(
                enabled = false,
                activeRecoveryToken = 3L,
                reportedRecoveryToken = 3L,
                callbackSuccess = false,
                interactive = false,
                deviceLocked = true,
                failureReason = "dismiss_cancelled",
            )

        assertTrue(decision.shouldIgnore)
        assertFalse(decision.shouldMarkSuccess)
    }

    @Test
    fun evaluate_ignoresResultsForStaleRecoveryTokens() {
        val decision =
            KeepAliveRecoveryResultPolicy.evaluate(
                enabled = true,
                activeRecoveryToken = 4L,
                reportedRecoveryToken = 3L,
                callbackSuccess = true,
                interactive = true,
                deviceLocked = false,
                failureReason = null,
            )

        assertTrue(decision.shouldIgnore)
        assertFalse(decision.shouldMarkSuccess)
    }

    @Test
    fun evaluate_requiresRecoveredScreenStateEvenWhenCallbackSucceeds() {
        val decision =
            KeepAliveRecoveryResultPolicy.evaluate(
                enabled = true,
                activeRecoveryToken = 7L,
                reportedRecoveryToken = 7L,
                callbackSuccess = true,
                interactive = false,
                deviceLocked = false,
                failureReason = "dismiss_cancelled",
            )

        assertFalse(decision.shouldIgnore)
        assertFalse(decision.shouldMarkSuccess)
        assertEquals("screen_not_interactive", decision.failureReason)
    }

    @Test
    fun evaluate_marksSuccessWhenDeviceIsAlreadyAwakeAndUnlocked() {
        val decision =
            KeepAliveRecoveryResultPolicy.evaluate(
                enabled = true,
                activeRecoveryToken = 9L,
                reportedRecoveryToken = 9L,
                callbackSuccess = false,
                interactive = true,
                deviceLocked = false,
                failureReason = "dismiss_cancelled",
            )

        assertFalse(decision.shouldIgnore)
        assertTrue(decision.shouldMarkSuccess)
        assertEquals(null, decision.failureReason)
    }

    @Test
    fun evaluate_preservesFailureWhenDeviceStillNeedsRecovery() {
        val decision =
            KeepAliveRecoveryResultPolicy.evaluate(
                enabled = true,
                activeRecoveryToken = 11L,
                reportedRecoveryToken = 11L,
                callbackSuccess = false,
                interactive = false,
                deviceLocked = true,
                failureReason = "dismiss_cancelled",
            )

        assertFalse(decision.shouldIgnore)
        assertFalse(decision.shouldMarkSuccess)
        assertEquals("dismiss_cancelled", decision.failureReason)
    }

    @Test
    fun evaluate_usesLiveStateFailureReasonWhenCallbackSucceedsButDeviceIsStillLocked() {
        val decision =
            KeepAliveRecoveryResultPolicy.evaluate(
                enabled = true,
                activeRecoveryToken = 12L,
                reportedRecoveryToken = 12L,
                callbackSuccess = true,
                interactive = true,
                deviceLocked = true,
                failureReason = null,
            )

        assertFalse(decision.shouldIgnore)
        assertFalse(decision.shouldMarkSuccess)
        assertEquals("device_still_locked", decision.failureReason)
    }
}
