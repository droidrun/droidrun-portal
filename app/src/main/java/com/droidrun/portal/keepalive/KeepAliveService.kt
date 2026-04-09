package com.droidrun.portal.keepalive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.droidrun.portal.R
import com.droidrun.portal.config.ConfigManager
import com.droidrun.portal.service.DroidrunAccessibilityService

class KeepAliveService : Service(), ConfigManager.ConfigChangeListener {

    companion object {
        private const val TAG = "KeepAliveService"
        private const val CHANNEL_ID = "keep_alive_channel"
        private const val NOTIFICATION_ID = 2004
        private const val POLL_INTERVAL_MS = 30_000L
        private const val WAKE_SETTLE_DELAY_MS = 750L

        const val ACTION_RECONCILE = "com.droidrun.portal.action.KEEP_ALIVE_RECONCILE"
        const val ACTION_STOP = "com.droidrun.portal.action.KEEP_ALIVE_STOP"

        @Volatile
        private var instance: KeepAliveService? = null

        fun isRunning(): Boolean = instance != null

        fun notifyRecoveryResult(
            context: Context,
            success: Boolean,
            reason: String? = null,
        ) {
            val service = instance
            if (service != null) {
                service.handleRecoveryResult(success, reason)
                return
            }
            if (success) {
                KeepAliveController.markRecoverySuccess(context.applicationContext)
            } else {
                KeepAliveController.markRecoveryFailure(
                    context.applicationContext,
                    reason ?: "recovery_failed",
                )
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val pollRunnable =
        object : Runnable {
            override fun run() {
                evaluateDeviceState("poll")
                mainHandler.postDelayed(this, POLL_INTERVAL_MS)
            }
        }
    private val screenStateReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val action = intent?.action ?: return
                evaluateDeviceState(action)
            }
        }

    private var steadyWakeLock: PowerManager.WakeLock? = null
    private var recoveryWakeLock: PowerManager.WakeLock? = null
    private var receiverRegistered = false
    private var recoveryInFlight = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        ConfigManager.getInstance(this).addListener(this)
        createNotificationChannel()
        registerScreenStateReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val configManager = ConfigManager.getInstance(applicationContext)
        if (intent?.action == ACTION_STOP || !configManager.keepScreenAwakeEnabled) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, createNotification())
        ensureSteadyWakeLock()
        schedulePoll()
        evaluateDeviceState(intent?.action ?: ACTION_RECONCILE)
        return START_STICKY
    }

    override fun onDestroy() {
        ConfigManager.getInstance(this).removeListener(this)
        mainHandler.removeCallbacksAndMessages(null)
        if (receiverRegistered) {
            try {
                unregisterReceiver(screenStateReceiver)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to unregister screen-state receiver", e)
            }
            receiverRegistered = false
        }
        releaseWakeLocks()
        recoveryInFlight = false
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onOverlayVisibilityChanged(visible: Boolean) = Unit

    override fun onOverlayOffsetChanged(offset: Int) = Unit

    override fun onSocketServerEnabledChanged(enabled: Boolean) = Unit

    override fun onSocketServerPortChanged(port: Int) = Unit

    override fun onKeepScreenAwakeEnabledChanged(enabled: Boolean) {
        if (!enabled) {
            stopSelf()
        }
    }

    private fun schedulePoll() {
        mainHandler.removeCallbacks(pollRunnable)
        mainHandler.postDelayed(pollRunnable, POLL_INTERVAL_MS)
    }

    private fun evaluateDeviceState(trigger: String) {
        val appContext = applicationContext
        val status = KeepAliveController.getStatus(appContext)
        if (!status.enabled) {
            stopSelf()
            return
        }

        ensureSteadyWakeLock()

        val decision =
            KeepAliveRecoveryPolicy.evaluate(
                enabled = status.enabled,
                interactive = status.interactive,
                deviceLocked = status.deviceLocked,
                lastRecoveryAtMs = status.lastRecoveryAtMs,
                nowMs = System.currentTimeMillis(),
            )

        if (!decision.shouldAttemptRecovery) {
            if (status.interactive && !status.deviceLocked) {
                KeepAliveController.setDegradedReason(appContext, null)
            } else if (decision.degradedReason != null) {
                KeepAliveController.setDegradedReason(appContext, decision.degradedReason)
            }
            return
        }

        val recoveryAtMs = System.currentTimeMillis()
        KeepAliveController.noteRecoveryAttempt(appContext, recoveryAtMs)

        if (decision.shouldWakeDisplay) {
            wakeDisplay()
        }

        if (decision.shouldLaunchRecoveryActivity) {
            launchRecoveryActivity("locked:$trigger", recoveryAtMs)
            return
        }

        if (decision.shouldWakeDisplay) {
            mainHandler.postDelayed(
                {
                    val refreshedStatus = KeepAliveController.getStatus(appContext)
                    if (!refreshedStatus.interactive || refreshedStatus.deviceLocked) {
                        launchRecoveryActivity("wake_check:$trigger", recoveryAtMs)
                    } else {
                        KeepAliveController.markRecoverySuccess(appContext, recoveryAtMs)
                    }
                },
                WAKE_SETTLE_DELAY_MS,
            )
        }
    }

    private fun wakeDisplay() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (powerManager == null) {
                KeepAliveController.markRecoveryFailure(
                    applicationContext,
                    "power_manager_unavailable",
                )
                return
            }
            if (recoveryWakeLock == null) {
                @Suppress("DEPRECATION")
                recoveryWakeLock =
                    powerManager.newWakeLock(
                        PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                        "$packageName:keep_alive_recovery",
                    ).apply { setReferenceCounted(false) }
            }
            recoveryWakeLock?.acquire(5_000L)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to acquire recovery wake lock", t)
            KeepAliveController.markRecoveryFailure(
                applicationContext,
                "wake_lock_acquire_failed",
            )
        }
    }

    private fun launchRecoveryActivity(
        reason: String,
        recoveryAtMs: Long,
    ) {
        if (recoveryInFlight) return

        val service = DroidrunAccessibilityService.getInstance()
        if (service == null) {
            KeepAliveController.markRecoveryFailure(
                applicationContext,
                "accessibility_service_unavailable",
                recoveryAtMs,
            )
            return
        }

        val launched = service.launchKeepAliveRecoveryActivity(reason)
        if (!launched) {
            KeepAliveController.markRecoveryFailure(
                applicationContext,
                "recovery_activity_launch_failed",
                recoveryAtMs,
            )
            return
        }

        recoveryInFlight = true
    }

    private fun handleRecoveryResult(
        success: Boolean,
        reason: String?,
    ) {
        mainHandler.post {
            recoveryInFlight = false
            if (success) {
                KeepAliveController.markRecoverySuccess(applicationContext)
            } else {
                KeepAliveController.markRecoveryFailure(
                    applicationContext,
                    reason ?: "dismiss_failed",
                )
            }
        }
    }

    private fun ensureSteadyWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (powerManager == null) {
                KeepAliveController.setDegradedReason(
                    applicationContext,
                    "power_manager_unavailable",
                )
                return
            }
            if (steadyWakeLock == null) {
                @Suppress("DEPRECATION")
                steadyWakeLock =
                    powerManager.newWakeLock(
                        PowerManager.SCREEN_BRIGHT_WAKE_LOCK,
                        "$packageName:keep_alive_steady",
                    ).apply { setReferenceCounted(false) }
            }
            if (steadyWakeLock?.isHeld != true) {
                steadyWakeLock?.acquire()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to acquire steady wake lock", t)
            KeepAliveController.setDegradedReason(
                applicationContext,
                "steady_wake_lock_failed",
            )
        }
    }

    private fun releaseWakeLocks() {
        try {
            if (recoveryWakeLock?.isHeld == true) {
                recoveryWakeLock?.release()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to release recovery wake lock", t)
        }

        try {
            if (steadyWakeLock?.isHeld == true) {
                steadyWakeLock?.release()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to release steady wake lock", t)
        }
    }

    private fun registerScreenStateReceiver() {
        val filter =
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(
                    screenStateReceiver,
                    filter,
                    Context.RECEIVER_NOT_EXPORTED,
                )
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(screenStateReceiver, filter)
            }
            receiverRegistered = true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register screen-state receiver", e)
            receiverRegistered = false
        }
    }

    private fun createNotificationChannel() {
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.keep_screen_awake_notification_title),
                NotificationManager.IMPORTANCE_LOW,
            )
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        val stopIntent =
            Intent(this, KeepAliveService::class.java).apply {
                action = ACTION_STOP
            }
        val stopPendingIntent =
            PendingIntent.getService(
                this,
                0,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.keep_screen_awake_notification_title))
            .setContentText(getString(R.string.keep_screen_awake_notification_text))
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.keep_screen_awake_notification_stop),
                stopPendingIntent,
            )
            .build()
    }
}
