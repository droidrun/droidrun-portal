package com.mobilerun.portal

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log
import com.mobilerun.portal.config.ConfigManager
import com.mobilerun.portal.keepalive.KeepAliveController
import com.mobilerun.portal.service.http402BlockedPresentationState
import com.mobilerun.portal.state.AppForegroundTransitionTracker
import com.mobilerun.portal.state.AppVisibilityTracker
import com.mobilerun.portal.state.ConnectionStateManager

class PortalApplication : Application() {
    private val foregroundTransitionTracker =
        AppForegroundTransitionTracker(
            onForeground = ::onAppForegrounded,
            onBackground = ::onAppBackgrounded,
        )

    override fun onCreate() {
        super.onCreate()
        val configManager = ConfigManager.getInstance(this)
        val http402Snapshot = configManager.reverseJoinHttp402Snapshot()
        http402BlockedPresentationState(
            blocked = http402Snapshot.blocked,
            explicitlyDisconnected = http402Snapshot.explicitlyDisconnected,
        )?.let(ConnectionStateManager::setState)
        registerActivityLifecycleCallbacks(
            object : ActivityLifecycleCallbacks {
                override fun onActivityCreated(
                    activity: Activity,
                    savedInstanceState: Bundle?,
                ) = Unit

                override fun onActivityStarted(activity: Activity) {
                    foregroundTransitionTracker.onActivityStarted()
                }

                override fun onActivityResumed(activity: Activity) = Unit

                override fun onActivityPaused(activity: Activity) = Unit

                override fun onActivityStopped(activity: Activity) {
                    foregroundTransitionTracker.onActivityStopped()
                }

                override fun onActivitySaveInstanceState(
                    activity: Activity,
                    outState: Bundle,
                ) = Unit

                override fun onActivityDestroyed(activity: Activity) = Unit
            },
        )
    }

    private fun onAppForegrounded() {
        AppVisibilityTracker.setForeground(true)
        KeepAliveController.retryStartupIfEnabledAndInactive(this)?.let { reason ->
            Log.w(
                TAG,
                "Deferred keep-awake startup still blocked after app entered foreground: $reason",
            )
        }
    }

    private fun onAppBackgrounded() {
        AppVisibilityTracker.setForeground(false)
    }

    companion object {
        private const val TAG = "PortalApplication"
    }
}
