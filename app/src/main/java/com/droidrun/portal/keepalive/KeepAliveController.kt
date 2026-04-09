package com.droidrun.portal.keepalive

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import com.droidrun.portal.config.ConfigManager
import org.json.JSONObject

object KeepAliveController {
    data class BestEffortReconcileResult(
        val deferredReason: String? = null,
    )

    fun setEnabled(context: Context, enabled: Boolean) {
        if (enabled) {
            enable(context)
        } else {
            disable(context)
        }
    }

    fun enable(context: Context) {
        val appContext = context.applicationContext
        val configManager = ConfigManager.getInstance(appContext)
        if (configManager.keepScreenAwakeEnabled && KeepAliveService.isRunning()) {
            return
        }
        configManager.setKeepScreenAwakeEnabledWithNotification(true)
        try {
            KeepAliveServiceRuntime.start(appContext)
        } catch (e: KeepAliveStartupException) {
            configManager.setKeepScreenAwakeEnabledWithNotification(false)
            configManager.clearKeepAliveRuntimeState()
            KeepAliveServiceRuntime.stop(appContext)
            throw e
        }
    }

    fun disable(context: Context) {
        val appContext = context.applicationContext
        val configManager = ConfigManager.getInstance(appContext)
        configManager.setKeepScreenAwakeEnabledWithNotification(false)
        configManager.clearKeepAliveRuntimeState()
        KeepAliveServiceRuntime.stop(appContext)
    }

    fun reconcile(context: Context) {
        val appContext = context.applicationContext
        val configManager = ConfigManager.getInstance(appContext)
        if (configManager.keepScreenAwakeEnabled) {
            KeepAliveServiceRuntime.start(appContext)
        } else {
            KeepAliveServiceRuntime.stop(appContext)
        }
    }

    fun reconcileBestEffort(context: Context): BestEffortReconcileResult {
        return try {
            reconcile(context)
            BestEffortReconcileResult()
        } catch (e: KeepAliveStartupException) {
            BestEffortReconcileResult(deferredReason = e.reason)
        }
    }

    fun retryStartupIfEnabledAndInactive(context: Context): String? {
        val appContext = context.applicationContext
        val configManager = ConfigManager.getInstance(appContext)
        if (!configManager.keepScreenAwakeEnabled || KeepAliveService.isRunning()) {
            return null
        }
        return try {
            KeepAliveServiceRuntime.start(appContext)
            null
        } catch (e: KeepAliveStartupException) {
            e.reason
        }
    }

    fun getStatus(context: Context): KeepAliveStatus {
        val appContext = context.applicationContext
        val configManager = ConfigManager.getInstance(appContext)
        return KeepAliveStatus(
            enabled = configManager.keepScreenAwakeEnabled,
            serviceActive = KeepAliveService.isRunning(),
            interactive = isInteractive(appContext),
            deviceLocked = isDeviceLocked(appContext),
            lastRecoveryAtMs = configManager.keepAliveLastRecoveryAtMs,
            consecutiveRecoveryFailures = configManager.keepAliveConsecutiveRecoveryFailures,
            degradedReason = configManager.keepAliveDegradedReason,
        )
    }

    fun getMutationResultStatus(
        context: Context,
        requestedEnabled: Boolean,
    ): KeepAliveStatus =
        getStatus(context).withTargetState(
            enabled = requestedEnabled,
            serviceActive = requestedEnabled,
        )

    fun getStatusJson(context: Context): JSONObject = getStatus(context).toJson()

    fun getMutationResultStatusJson(
        context: Context,
        requestedEnabled: Boolean,
    ): JSONObject = getMutationResultStatus(context, requestedEnabled).toJson()

    fun noteRecoveryAttempt(
        context: Context,
        atMs: Long = System.currentTimeMillis(),
    ) {
        ConfigManager.getInstance(context.applicationContext).keepAliveLastRecoveryAttemptAtMs = atMs
    }

    fun markRecoverySuccess(
        context: Context,
        atMs: Long = System.currentTimeMillis(),
    ) {
        val configManager = ConfigManager.getInstance(context.applicationContext)
        configManager.keepAliveLastRecoveryAtMs = atMs
        configManager.keepAliveLastRecoveryAttemptAtMs = 0L
        configManager.keepAliveConsecutiveRecoveryFailures = 0
        configManager.keepAliveDegradedReason = null
    }

    fun markRecoveryFailure(
        context: Context,
        reason: String,
        atMs: Long = System.currentTimeMillis(),
    ) {
        val configManager = ConfigManager.getInstance(context.applicationContext)
        configManager.keepAliveLastRecoveryAtMs = atMs
        configManager.keepAliveLastRecoveryAttemptAtMs = atMs
        configManager.keepAliveConsecutiveRecoveryFailures =
            configManager.keepAliveConsecutiveRecoveryFailures + 1
        configManager.keepAliveDegradedReason = reason
    }

    fun setDegradedReason(
        context: Context,
        reason: String?,
    ) {
        ConfigManager.getInstance(context.applicationContext).keepAliveDegradedReason = reason
    }

    private fun isInteractive(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        return powerManager?.isInteractive ?: false
    }

    private fun isDeviceLocked(context: Context): Boolean {
        val keyguardManager =
            context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        return keyguardManager?.isDeviceLocked ?: false
    }
}
