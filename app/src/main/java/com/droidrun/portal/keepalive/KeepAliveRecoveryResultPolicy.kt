package com.droidrun.portal.keepalive

data class KeepAliveRecoveryResultDecision(
    val shouldIgnore: Boolean,
    val shouldMarkSuccess: Boolean,
    val failureReason: String? = null,
)

object KeepAliveRecoveryResultPolicy {
    fun evaluate(
        enabled: Boolean,
        activeRecoveryToken: Long?,
        reportedRecoveryToken: Long,
        callbackSuccess: Boolean,
        interactive: Boolean,
        deviceLocked: Boolean,
        failureReason: String?,
    ): KeepAliveRecoveryResultDecision {
        if (!enabled) {
            return KeepAliveRecoveryResultDecision(
                shouldIgnore = true,
                shouldMarkSuccess = false,
            )
        }

        if (activeRecoveryToken == null || activeRecoveryToken != reportedRecoveryToken) {
            return KeepAliveRecoveryResultDecision(
                shouldIgnore = true,
                shouldMarkSuccess = false,
            )
        }

        if (callbackSuccess || (interactive && !deviceLocked)) {
            return KeepAliveRecoveryResultDecision(
                shouldIgnore = false,
                shouldMarkSuccess = true,
            )
        }

        return KeepAliveRecoveryResultDecision(
            shouldIgnore = false,
            shouldMarkSuccess = false,
            failureReason = failureReason ?: "dismiss_failed",
        )
    }
}
