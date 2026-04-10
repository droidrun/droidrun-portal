package com.droidrun.portal.keepalive

data class KeepAliveRecoveryActivityAttempt(
    val generation: Long,
    val recoveryToken: Long,
)

class KeepAliveRecoveryActivityAttemptState {
    private var generation = 0L
    private var currentAttempt: KeepAliveRecoveryActivityAttempt? = null
    private var dismissCallbackState: KeepAliveDismissCallbackState = KeepAliveDismissCallbackState.None

    fun beginAttempt(recoveryToken: Long): KeepAliveRecoveryActivityAttempt {
        generation += 1L
        dismissCallbackState = KeepAliveDismissCallbackState.None
        return KeepAliveRecoveryActivityAttempt(
            generation = generation,
            recoveryToken = recoveryToken,
        ).also { currentAttempt = it }
    }

    fun currentAttempt(): KeepAliveRecoveryActivityAttempt? = currentAttempt

    fun currentDismissCallbackState(): KeepAliveDismissCallbackState = dismissCallbackState

    fun isCurrentGeneration(generation: Long): Boolean = currentAttempt?.generation == generation

    fun updateDismissCallbackState(
        generation: Long,
        newState: KeepAliveDismissCallbackState,
    ): Boolean {
        if (!isCurrentGeneration(generation)) {
            return false
        }
        dismissCallbackState = newState
        return true
    }
}
