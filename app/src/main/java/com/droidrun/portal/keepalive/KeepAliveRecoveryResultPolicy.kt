package com.droidrun.portal.keepalive

data class KeepAliveRecoveryResultDecision(
    val shouldIgnore: Boolean,
    val shouldMarkSuccess: Boolean,
    val failureReason: String? = null,
)

object KeepAliveRecoveryResultPolicy {
    private fun stateFailureReason(
        interactive: Boolean,
        deviceLocked: Boolean,
    ): String {
        return if (deviceLocked) {
            "device_still_locked"
        } else if (!interactive) {
            "screen_not_interactive"
        } else {
            "dismiss_failed"
        }
    }

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

        if (interactive && !deviceLocked) {
            return KeepAliveRecoveryResultDecision(
                shouldIgnore = false,
                shouldMarkSuccess = true,
            )
        }

        val resolvedFailureReason =
            if (callbackSuccess) {
                stateFailureReason(interactive, deviceLocked)
            } else {
                failureReason ?: stateFailureReason(interactive, deviceLocked)
            }

        return KeepAliveRecoveryResultDecision(
            shouldIgnore = false,
            shouldMarkSuccess = false,
            failureReason = resolvedFailureReason,
        )
    }

    fun evaluatePersisted(
        enabled: Boolean,
        activeRecoveryToken: Long?,
        reportedRecoveryToken: Long,
        callbackSuccess: Boolean,
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

        return if (callbackSuccess) {
            KeepAliveRecoveryResultDecision(
                shouldIgnore = false,
                shouldMarkSuccess = true,
            )
        } else {
            KeepAliveRecoveryResultDecision(
                shouldIgnore = false,
                shouldMarkSuccess = false,
                failureReason = failureReason ?: "dismiss_failed",
            )
        }
    }
}
