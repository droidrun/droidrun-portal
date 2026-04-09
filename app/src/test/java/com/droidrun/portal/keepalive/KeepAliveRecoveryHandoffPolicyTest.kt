package com.droidrun.portal.keepalive

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeepAliveRecoveryHandoffPolicyTest {
    @Test
    fun deliveryDecision_persistsAndRestartsWhenServiceIsAbsentButFeatureIsEnabled() {
        val decision =
            KeepAliveRecoveryHandoffPolicy.deliveryDecision(
                hasLiveService = false,
                keepAliveEnabled = true,
            )

        assertFalse(decision.shouldHandleWithLiveService)
        assertTrue(decision.shouldPersistPendingResult)
        assertTrue(decision.shouldStartServiceBestEffort)
    }

    @Test
    fun deliveryDecision_ignoresLateCallbackWhenFeatureIsDisabled() {
        val decision =
            KeepAliveRecoveryHandoffPolicy.deliveryDecision(
                hasLiveService = false,
                keepAliveEnabled = false,
            )

        assertFalse(decision.shouldHandleWithLiveService)
        assertFalse(decision.shouldPersistPendingResult)
        assertFalse(decision.shouldStartServiceBestEffort)
    }

    @Test
    fun handoffDecision_consumesPendingResultForMatchingActiveToken() {
        val decision =
            KeepAliveRecoveryHandoffPolicy.handoffDecision(
                activeRecoveryToken = 41L,
                recoveryActivityInFlight = true,
                pendingRecoveryResultToken = 41L,
                lastRecoveryAttemptAtMs = 1_000L,
                nowMs = 2_000L,
            )

        assertTrue(decision.shouldConsumePendingResult)
        assertFalse(decision.shouldSuppressRecoveryEvaluation)
        assertFalse(decision.shouldClearHandoffState)
    }

    @Test
    fun handoffDecision_suppressesFreshTrackedRecoveryUntilGraceWindowExpires() {
        val decision =
            KeepAliveRecoveryHandoffPolicy.handoffDecision(
                activeRecoveryToken = 55L,
                recoveryActivityInFlight = true,
                pendingRecoveryResultToken = 0L,
                lastRecoveryAttemptAtMs = 1_000L,
                nowMs = 5_000L,
            )

        assertFalse(decision.shouldConsumePendingResult)
        assertTrue(decision.shouldSuppressRecoveryEvaluation)
        assertFalse(decision.shouldClearHandoffState)
    }

    @Test
    fun handoffDecision_clearsStaleTrackedRecoveryAfterGraceWindow() {
        val decision =
            KeepAliveRecoveryHandoffPolicy.handoffDecision(
                activeRecoveryToken = 73L,
                recoveryActivityInFlight = true,
                pendingRecoveryResultToken = 0L,
                lastRecoveryAttemptAtMs = 1_000L,
                nowMs = 20_500L,
            )

        assertFalse(decision.shouldConsumePendingResult)
        assertFalse(decision.shouldSuppressRecoveryEvaluation)
        assertTrue(decision.shouldClearHandoffState)
    }

    @Test
    fun handoffDecision_clearsInvalidPendingResultWithoutActiveToken() {
        val decision =
            KeepAliveRecoveryHandoffPolicy.handoffDecision(
                activeRecoveryToken = 0L,
                recoveryActivityInFlight = true,
                pendingRecoveryResultToken = 88L,
                lastRecoveryAttemptAtMs = 1_000L,
                nowMs = 2_000L,
            )

        assertFalse(decision.shouldConsumePendingResult)
        assertFalse(decision.shouldSuppressRecoveryEvaluation)
        assertTrue(decision.shouldClearHandoffState)
    }
}
