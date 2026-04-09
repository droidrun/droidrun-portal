package com.droidrun.portal.keepalive

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
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
    private val attemptState = KeepAliveRecoveryActivityAttemptState()
    private var completed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        restartDismissFlow()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        restartDismissFlow()
    }

    override fun onResume() {
        super.onResume()
        val attempt = attemptState.currentAttempt() ?: return
        KeepAliveRecoveryActivityStatePolicy.resultForResume(sampleScreenState())?.let { result ->
            complete(attempt, success = result.success, reason = result.reason)
        }
    }

    private fun restartDismissFlow() {
        mainHandler.removeCallbacksAndMessages(null)
        completed = false
        attemptDismiss(attemptState.beginAttempt(readRecoveryTokenFromIntent()))
    }

    private fun attemptDismiss(attempt: KeepAliveRecoveryActivityAttempt) {
        val keyguardManager = getSystemService(KEYGUARD_SERVICE) as? KeyguardManager
        if (keyguardManager == null) {
            complete(attempt, success = false, reason = "keyguard_manager_unavailable")
            return
        }

        KeepAliveRecoveryActivityStatePolicy.resultForResume(
            sampleScreenState(keyguardManager = keyguardManager),
        )?.let { result ->
            complete(attempt, success = result.success, reason = result.reason)
            return
        }

        mainHandler.postDelayed(
            {
                if (!attemptState.isCurrentGeneration(attempt.generation)) {
                    return@postDelayed
                }
                val result =
                    KeepAliveRecoveryActivityStatePolicy.resultForTimeout(
                        sampleScreenState(keyguardManager = keyguardManager),
                        attemptState.currentDismissCallbackState(),
                    )
                complete(attempt, success = result.success, reason = result.reason)
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
                            attempt,
                            KeepAliveDismissCallbackState.Succeeded,
                            keyguardManager,
                        )
                    }

                    override fun onDismissCancelled() {
                        handleDismissCallback(
                            attempt,
                            KeepAliveDismissCallbackState.Failed("dismiss_cancelled"),
                            keyguardManager,
                        )
                    }

                    override fun onDismissError() {
                        handleDismissCallback(
                            attempt,
                            KeepAliveDismissCallbackState.Failed("dismiss_error"),
                            keyguardManager,
                        )
                    }
                },
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request keyguard dismissal", e)
            handleDismissCallback(
                attempt,
                KeepAliveDismissCallbackState.Failed("dismiss_exception"),
                keyguardManager,
            )
        }
    }

    private fun handleDismissCallback(
        attempt: KeepAliveRecoveryActivityAttempt,
        newState: KeepAliveDismissCallbackState,
        keyguardManager: KeyguardManager,
    ) {
        if (!attemptState.updateDismissCallbackState(attempt.generation, newState)) {
            return
        }
        KeepAliveRecoveryActivityStatePolicy.resultForDismissCallback(
            sampleScreenState(keyguardManager = keyguardManager),
            attemptState.currentDismissCallbackState(),
        )?.let { result ->
            complete(attempt, success = result.success, reason = result.reason)
        }
    }

    private fun complete(
        attempt: KeepAliveRecoveryActivityAttempt,
        success: Boolean,
        reason: String?,
    ) {
        if (completed || !attemptState.isCurrentGeneration(attempt.generation)) return
        completed = true
        mainHandler.removeCallbacksAndMessages(null)
        KeepAliveService.notifyRecoveryResult(
            applicationContext,
            attempt.recoveryToken,
            success,
            reason,
        )
        finish()
        overridePendingTransition(0, 0)
    }

    private fun readRecoveryTokenFromIntent(): Long {
        return intent?.getLongExtra(EXTRA_RECOVERY_TOKEN, -1L) ?: -1L
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
