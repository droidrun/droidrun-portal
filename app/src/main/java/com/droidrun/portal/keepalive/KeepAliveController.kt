package com.droidrun.portal.keepalive

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import com.droidrun.portal.config.ConfigManager
import org.json.JSONObject

object KeepAliveController {
    fun setEnabled(context: Context, enabled: Boolean) {
        val appContext = context.applicationContext
        val configManager = ConfigManager.getInstance(appContext)
        configManager.setKeepScreenAwakeEnabledWithNotification(enabled)
        if (!enabled) {
            configManager.clearKeepAliveRuntimeState()
        }
        reconcile(appContext)
    }

    fun reconcile(context: Context) {
        val appContext = context.applicationContext
        val configManager = ConfigManager.getInstance(appContext)
        if (configManager.keepScreenAwakeEnabled) {
            startService(appContext)
        } else {
            stopService(appContext)
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

    fun getStatusJson(context: Context): JSONObject = getStatus(context).toJson()

    fun noteRecoveryAttempt(
        context: Context,
        atMs: Long = System.currentTimeMillis(),
    ) {
        ConfigManager.getInstance(context.applicationContext).keepAliveLastRecoveryAtMs = atMs
    }

    fun markRecoverySuccess(
        context: Context,
        atMs: Long = System.currentTimeMillis(),
    ) {
        val configManager = ConfigManager.getInstance(context.applicationContext)
        configManager.keepAliveLastRecoveryAtMs = atMs
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

    private fun startService(context: Context) {
        val intent =
            Intent(context, KeepAliveService::class.java).apply {
                action = KeepAliveService.ACTION_RECONCILE
            }
        context.startForegroundService(intent)
    }

    private fun stopService(context: Context) {
        val intent =
            Intent(context, KeepAliveService::class.java).apply {
                action = KeepAliveService.ACTION_STOP
            }
        context.startService(intent)
        context.stopService(Intent(context, KeepAliveService::class.java))
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
