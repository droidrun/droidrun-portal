package com.mobilerun.portal.keepalive

import org.json.JSONObject

data class KeepAliveStatus(
    val enabled: Boolean,
    val serviceActive: Boolean,
    val interactive: Boolean,
    val deviceLocked: Boolean,
    val lastRecoveryAtMs: Long,
    val consecutiveRecoveryFailures: Int,
    val degradedReason: String?,
) {
    fun withTargetState(
        enabled: Boolean,
        serviceActive: Boolean,
    ): KeepAliveStatus =
        copy(
            enabled = enabled,
            serviceActive = serviceActive,
        )

    fun toJson(): JSONObject =
        JSONObject().apply {
            put("enabled", enabled)
            put("serviceActive", serviceActive)
            put("interactive", interactive)
            put("deviceLocked", deviceLocked)
            put("lastRecoveryAtMs", lastRecoveryAtMs)
            put("consecutiveRecoveryFailures", consecutiveRecoveryFailures)
            put("degradedReason", degradedReason ?: JSONObject.NULL)
        }
}
