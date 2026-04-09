package com.droidrun.portal.keepalive

import android.app.Activity
import android.app.KeyguardManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.WindowManager

class KeepAliveRecoveryActivity : Activity() {

    companion object {
        private const val TAG = "KeepAliveRecovery"
        private const val FINISH_TIMEOUT_MS = 2_500L
        const val EXTRA_REASON = "extra_reason"
        const val EXTRA_RECOVERY_TOKEN = "extra_recovery_token"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var completed = false
    private var dismissCallbackState: KeepAliveDismissCallbackState = KeepAliveDismissCallbackState.None
    private val recoveryToken by lazy {
        intent?.getLongExtra(EXTRA_RECOVERY_TOKEN, -1L) ?: -1L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        attemptDismiss()
    }

    override fun onResume() {
        super.onResume()
        KeepAliveRecoveryActivityStatePolicy.resultForResume(sampleScreenState())?.let { result ->
            complete(success = result.success, reason = result.reason)
        }
    }

    private fun attemptDismiss() {
        val keyguardManager = getSystemService(KEYGUARD_SERVICE) as? KeyguardManager
        if (keyguardManager == null) {
            complete(success = false, reason = "keyguard_manager_unavailable")
            return
        }

        KeepAliveRecoveryActivityStatePolicy.resultForResume(
            sampleScreenState(keyguardManager = keyguardManager),
        )?.let { result ->
            complete(success = result.success, reason = result.reason)
            return
        }

        mainHandler.postDelayed(
            {
                val result =
                    KeepAliveRecoveryActivityStatePolicy.resultForTimeout(
                        sampleScreenState(keyguardManager = keyguardManager),
                        dismissCallbackState,
                    )
                complete(success = result.success, reason = result.reason)
            },
            FINISH_TIMEOUT_MS,
        )

        if (!keyguardManager.isDeviceLocked) {
            return
        }

        try {
            keyguardManager.requestDismissKeyguard(
                this,
                object : KeyguardManager.KeyguardDismissCallback() {
                    override fun onDismissSucceeded() {
                        handleDismissCallback(
                            KeepAliveDismissCallbackState.Succeeded,
                            keyguardManager,
                        )
                    }

                    override fun onDismissCancelled() {
                        handleDismissCallback(
                            KeepAliveDismissCallbackState.Failed("dismiss_cancelled"),
                            keyguardManager,
                        )
                    }

                    override fun onDismissError() {
                        handleDismissCallback(
                            KeepAliveDismissCallbackState.Failed("dismiss_error"),
                            keyguardManager,
                        )
                    }
                },
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request keyguard dismissal", e)
            handleDismissCallback(
                KeepAliveDismissCallbackState.Failed("dismiss_exception"),
                keyguardManager,
            )
        }
    }

    private fun handleDismissCallback(
        newState: KeepAliveDismissCallbackState,
        keyguardManager: KeyguardManager,
    ) {
        dismissCallbackState = newState
        KeepAliveRecoveryActivityStatePolicy.resultForDismissCallback(
            sampleScreenState(keyguardManager = keyguardManager),
            dismissCallbackState,
        )?.let { result ->
            complete(success = result.success, reason = result.reason)
        }
    }

    private fun complete(
        success: Boolean,
        reason: String?,
    ) {
        if (completed) return
        completed = true
        mainHandler.removeCallbacksAndMessages(null)
        KeepAliveService.notifyRecoveryResult(recoveryToken, success, reason)
        finish()
        overridePendingTransition(0, 0)
    }

    private fun sampleScreenState(keyguardManager: KeyguardManager? = null): KeepAliveRecoveryScreenState {
        val resolvedKeyguardManager =
            keyguardManager ?: (getSystemService(KEYGUARD_SERVICE) as? KeyguardManager)
        val powerManager = getSystemService(POWER_SERVICE) as? PowerManager
        return KeepAliveRecoveryScreenState(
            interactive = powerManager?.isInteractive == true,
            deviceLocked = resolvedKeyguardManager?.isDeviceLocked != false,
        )
    }
}
