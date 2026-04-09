package com.droidrun.portal.keepalive

data class KeepAliveRecoveryDecision(
    val shouldAttemptRecovery: Boolean,
    val shouldWakeDisplay: Boolean,
    val shouldLaunchRecoveryActivity: Boolean,
    val degradedReason: String? = null,
)

object KeepAliveRecoveryPolicy {
    const val RECOVERY_THROTTLE_MS = 60_000L

    fun evaluate(
        enabled: Boolean,
        interactive: Boolean,
        deviceLocked: Boolean,
        lastRecoveryAtMs: Long,
        nowMs: Long,
        throttleMs: Long = RECOVERY_THROTTLE_MS,
    ): KeepAliveRecoveryDecision {
        if (!enabled) {
            return KeepAliveRecoveryDecision(
                shouldAttemptRecovery = false,
                shouldWakeDisplay = false,
                shouldLaunchRecoveryActivity = false,
            )
        }

        val needsRecovery = !interactive || deviceLocked
        if (!needsRecovery) {
            return KeepAliveRecoveryDecision(
                shouldAttemptRecovery = false,
                shouldWakeDisplay = false,
                shouldLaunchRecoveryActivity = false,
            )
        }

        if (lastRecoveryAtMs > 0L && nowMs - lastRecoveryAtMs < throttleMs) {
            return KeepAliveRecoveryDecision(
                shouldAttemptRecovery = false,
                shouldWakeDisplay = false,
                shouldLaunchRecoveryActivity = false,
                degradedReason = "recovery_throttled",
            )
        }

        return KeepAliveRecoveryDecision(
            shouldAttemptRecovery = true,
            shouldWakeDisplay = !interactive,
            shouldLaunchRecoveryActivity = deviceLocked,
        )
    }
}
