package com.droidrun.portal.keepalive

import android.app.Activity
import android.app.KeyguardManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager

class KeepAliveRecoveryActivity : Activity() {

    companion object {
        private const val TAG = "KeepAliveRecovery"
        private const val FINISH_TIMEOUT_MS = 2_500L
        const val EXTRA_REASON = "extra_reason"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var completed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        attemptDismiss()
    }

    override fun onResume() {
        super.onResume()
        val keyguardManager = getSystemService(KEYGUARD_SERVICE) as? KeyguardManager
        if (keyguardManager?.isDeviceLocked == false) {
            complete(success = true, reason = null)
        }
    }

    private fun attemptDismiss() {
        val keyguardManager = getSystemService(KEYGUARD_SERVICE) as? KeyguardManager
        if (keyguardManager == null) {
            complete(success = false, reason = "keyguard_manager_unavailable")
            return
        }

        if (!keyguardManager.isDeviceLocked) {
            complete(success = true, reason = null)
            return
        }

        mainHandler.postDelayed(
            {
                val stillLocked = keyguardManager.isDeviceLocked
                complete(
                    success = !stillLocked,
                    reason = if (stillLocked) "dismiss_timeout" else null,
                )
            },
            FINISH_TIMEOUT_MS,
        )

        try {
            keyguardManager.requestDismissKeyguard(
                this,
                object : KeyguardManager.KeyguardDismissCallback() {
                    override fun onDismissSucceeded() {
                        complete(success = true, reason = null)
                    }

                    override fun onDismissCancelled() {
                        complete(success = false, reason = "dismiss_cancelled")
                    }

                    override fun onDismissError() {
                        complete(success = false, reason = "dismiss_error")
                    }
                },
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request keyguard dismissal", e)
            complete(success = false, reason = "dismiss_exception")
        }
    }

    private fun complete(
        success: Boolean,
        reason: String?,
    ) {
        if (completed) return
        completed = true
        mainHandler.removeCallbacksAndMessages(null)
        KeepAliveService.notifyRecoveryResult(applicationContext, success, reason)
        finish()
        overridePendingTransition(0, 0)
    }
}
