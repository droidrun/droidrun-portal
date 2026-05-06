package com.mobilerun.portal.keepalive

data class KeepAliveRecoveryScreenState(
    val interactive: Boolean,
    val deviceLocked: Boolean,
)

data class KeepAliveRecoveryActivityResult(
    val success: Boolean,
    val reason: String? = null,
)

sealed class KeepAliveDismissCallbackState {
    object None : KeepAliveDismissCallbackState()

    object Succeeded : KeepAliveDismissCallbackState()

    data class Failed(val reason: String) : KeepAliveDismissCallbackState()
}

object KeepAliveRecoveryActivityStatePolicy {
    private fun stateFailureReason(screenState: KeepAliveRecoveryScreenState): String {
        return if (screenState.deviceLocked) {
            "device_still_locked"
        } else {
            "screen_not_interactive"
        }
    }

    fun resultForResume(screenState: KeepAliveRecoveryScreenState): KeepAliveRecoveryActivityResult? {
        if (screenState.interactive && !screenState.deviceLocked) {
            return KeepAliveRecoveryActivityResult(success = true)
        }
        return null
    }

    fun resultForDismissCallback(
        screenState: KeepAliveRecoveryScreenState,
        dismissCallbackState: KeepAliveDismissCallbackState,
    ): KeepAliveRecoveryActivityResult? {
        return when {
            screenState.interactive && !screenState.deviceLocked ->
                KeepAliveRecoveryActivityResult(success = true)
            dismissCallbackState is KeepAliveDismissCallbackState.Failed ->
                null
            dismissCallbackState is KeepAliveDismissCallbackState.Succeeded ->
                null
            else ->
                null
        }
    }

    fun resultForTimeout(
        screenState: KeepAliveRecoveryScreenState,
        dismissCallbackState: KeepAliveDismissCallbackState = KeepAliveDismissCallbackState.None,
    ): KeepAliveRecoveryActivityResult {
        return when {
            screenState.interactive && !screenState.deviceLocked ->
                KeepAliveRecoveryActivityResult(success = true)
            dismissCallbackState is KeepAliveDismissCallbackState.Failed ->
                KeepAliveRecoveryActivityResult(success = false, reason = dismissCallbackState.reason)
            else ->
                KeepAliveRecoveryActivityResult(success = false, reason = stateFailureReason(screenState))
        }
    }
}
