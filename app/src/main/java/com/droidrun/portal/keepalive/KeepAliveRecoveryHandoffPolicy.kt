package com.droidrun.portal.keepalive

data class KeepAliveRecoveryResultDeliveryDecision(
    val shouldHandleWithLiveService: Boolean,
    val shouldPersistPendingResult: Boolean,
    val shouldStartServiceBestEffort: Boolean,
)

data class KeepAliveRecoveryHandoffDecision(
    val shouldConsumePendingResult: Boolean = false,
    val shouldSuppressRecoveryEvaluation: Boolean = false,
    val shouldClearHandoffState: Boolean = false,
)

object KeepAliveRecoveryHandoffPolicy {
    const val RECOVERY_HANDOFF_GRACE_MS = 10_000L

    fun deliveryDecision(
        hasLiveService: Boolean,
        keepAliveEnabled: Boolean,
    ): KeepAliveRecoveryResultDeliveryDecision {
        return when {
            hasLiveService ->
                KeepAliveRecoveryResultDeliveryDecision(
                    shouldHandleWithLiveService = true,
                    shouldPersistPendingResult = false,
                    shouldStartServiceBestEffort = false,
                )
            keepAliveEnabled ->
                KeepAliveRecoveryResultDeliveryDecision(
                    shouldHandleWithLiveService = false,
                    shouldPersistPendingResult = true,
                    shouldStartServiceBestEffort = true,
                )
            else ->
                KeepAliveRecoveryResultDeliveryDecision(
                    shouldHandleWithLiveService = false,
                    shouldPersistPendingResult = false,
                    shouldStartServiceBestEffort = false,
                )
        }
    }

    fun handoffDecision(
        activeRecoveryToken: Long,
        ownerSessionId: String?,
        currentSessionId: String,
        recoveryActivityInFlight: Boolean,
        pendingRecoveryResultToken: Long,
        lastRecoveryAttemptAtMs: Long,
        nowMs: Long,
        graceMs: Long = RECOVERY_HANDOFF_GRACE_MS,
    ): KeepAliveRecoveryHandoffDecision {
        val hasActiveRecoveryToken = activeRecoveryToken > 0L
        val hasPendingRecoveryResult = pendingRecoveryResultToken > 0L
        val hasTrackedRecovery = hasActiveRecoveryToken || recoveryActivityInFlight || hasPendingRecoveryResult

        if (!hasTrackedRecovery) {
            return KeepAliveRecoveryHandoffDecision()
        }

        if (hasPendingRecoveryResult) {
            return if (hasActiveRecoveryToken && pendingRecoveryResultToken == activeRecoveryToken) {
                KeepAliveRecoveryHandoffDecision(shouldConsumePendingResult = true)
            } else {
                KeepAliveRecoveryHandoffDecision(shouldClearHandoffState = true)
            }
        }

        if (!hasActiveRecoveryToken) {
            return KeepAliveRecoveryHandoffDecision(shouldClearHandoffState = true)
        }

        if (ownerSessionId.isNullOrBlank() || ownerSessionId != currentSessionId) {
            return KeepAliveRecoveryHandoffDecision(shouldClearHandoffState = true)
        }

        if (!recoveryActivityInFlight) {
            return KeepAliveRecoveryHandoffDecision(shouldClearHandoffState = true)
        }

        val attemptAgeMs =
            if (lastRecoveryAttemptAtMs > 0L) {
                (nowMs - lastRecoveryAttemptAtMs).coerceAtLeast(0L)
            } else {
                Long.MAX_VALUE
            }

        return if (attemptAgeMs < graceMs) {
            KeepAliveRecoveryHandoffDecision(shouldSuppressRecoveryEvaluation = true)
        } else {
            KeepAliveRecoveryHandoffDecision(shouldClearHandoffState = true)
        }
    }
}
