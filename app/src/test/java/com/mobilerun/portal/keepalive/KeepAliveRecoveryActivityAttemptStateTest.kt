package com.mobilerun.portal.keepalive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeepAliveRecoveryActivityAttemptStateTest {
    @Test
    fun beginAttempt_refreshesRecoveryTokenAndGenerationOnReuse() {
        val state = KeepAliveRecoveryActivityAttemptState()

        val firstAttempt = state.beginAttempt(11L)
        state.updateDismissCallbackState(
            firstAttempt.generation,
            KeepAliveDismissCallbackState.Failed("dismiss_cancelled"),
        )

        val secondAttempt = state.beginAttempt(22L)

        assertEquals(1L, firstAttempt.generation)
        assertEquals(2L, secondAttempt.generation)
        assertEquals(22L, secondAttempt.recoveryToken)
        assertEquals(secondAttempt, state.currentAttempt())
        assertEquals(KeepAliveDismissCallbackState.None, state.currentDismissCallbackState())
    }

    @Test
    fun updateDismissCallbackState_ignoresStaleGenerationAfterReuse() {
        val state = KeepAliveRecoveryActivityAttemptState()

        val firstAttempt = state.beginAttempt(11L)
        val secondAttempt = state.beginAttempt(22L)

        assertFalse(
            state.updateDismissCallbackState(
                firstAttempt.generation,
                KeepAliveDismissCallbackState.Failed("dismiss_cancelled"),
            ),
        )
        assertTrue(state.isCurrentGeneration(secondAttempt.generation))
        assertFalse(state.isCurrentGeneration(firstAttempt.generation))
        assertEquals(KeepAliveDismissCallbackState.None, state.currentDismissCallbackState())
    }

    @Test
    fun updateDismissCallbackState_updatesCurrentAttemptOnly() {
        val state = KeepAliveRecoveryActivityAttemptState()
        val attempt = state.beginAttempt(33L)

        assertTrue(
            state.updateDismissCallbackState(
                attempt.generation,
                KeepAliveDismissCallbackState.Succeeded,
            ),
        )
        assertEquals(
            KeepAliveDismissCallbackState.Succeeded,
            state.currentDismissCallbackState(),
        )
    }
}
