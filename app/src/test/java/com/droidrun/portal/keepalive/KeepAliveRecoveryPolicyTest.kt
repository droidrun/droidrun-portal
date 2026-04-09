package com.droidrun.portal.keepalive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeepAliveRecoveryPolicyTest {
    @Test
    fun evaluate_returnsNoRecoveryWhenDisabled() {
        val decision =
            KeepAliveRecoveryPolicy.evaluate(
                enabled = false,
                interactive = false,
                deviceLocked = true,
                lastRecoveryAtMs = 0L,
                nowMs = 1_000L,
            )

        assertFalse(decision.shouldAttemptRecovery)
        assertFalse(decision.shouldWakeDisplay)
        assertFalse(decision.shouldLaunchRecoveryActivity)
        assertEquals(null, decision.degradedReason)
    }

    @Test
    fun evaluate_returnsNoRecoveryWhenDeviceIsInteractiveAndUnlocked() {
        val decision =
            KeepAliveRecoveryPolicy.evaluate(
                enabled = true,
                interactive = true,
                deviceLocked = false,
                lastRecoveryAtMs = 0L,
                nowMs = 1_000L,
            )

        assertFalse(decision.shouldAttemptRecovery)
        assertFalse(decision.shouldWakeDisplay)
        assertFalse(decision.shouldLaunchRecoveryActivity)
    }

    @Test
    fun evaluate_wakesDisplayWhenScreenIsOff() {
        val decision =
            KeepAliveRecoveryPolicy.evaluate(
                enabled = true,
                interactive = false,
                deviceLocked = false,
                lastRecoveryAtMs = 0L,
                nowMs = 1_000L,
            )

        assertTrue(decision.shouldAttemptRecovery)
        assertTrue(decision.shouldWakeDisplay)
        assertFalse(decision.shouldLaunchRecoveryActivity)
    }

    @Test
    fun evaluate_launchesRecoveryActivityWhenLocked() {
        val decision =
            KeepAliveRecoveryPolicy.evaluate(
                enabled = true,
                interactive = true,
                deviceLocked = true,
                lastRecoveryAtMs = 0L,
                nowMs = 1_000L,
            )

        assertTrue(decision.shouldAttemptRecovery)
        assertFalse(decision.shouldWakeDisplay)
        assertTrue(decision.shouldLaunchRecoveryActivity)
    }

    @Test
    fun evaluate_throttlesRecentRecoveryAttempts() {
        val decision =
            KeepAliveRecoveryPolicy.evaluate(
                enabled = true,
                interactive = false,
                deviceLocked = false,
                lastRecoveryAtMs = 5_000L,
                nowMs = 10_000L,
                throttleMs = 10_000L,
            )

        assertFalse(decision.shouldAttemptRecovery)
        assertFalse(decision.shouldWakeDisplay)
        assertFalse(decision.shouldLaunchRecoveryActivity)
        assertEquals("recovery_throttled", decision.degradedReason)
    }
}
