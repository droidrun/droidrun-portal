package com.mobilerun.portal.keepalive

import android.content.Context
import android.content.Intent
import android.os.Build

object KeepAliveServiceRuntime {
    private const val FOREGROUND_SERVICE_START_NOT_ALLOWED_EXCEPTION =
        "android.app.ForegroundServiceStartNotAllowedException"

    fun start(context: Context) {
        val appContext = context.applicationContext
        val intent =
            Intent(appContext, KeepAliveService::class.java).apply {
                action = KeepAliveService.ACTION_RECONCILE
            }
        try {
            appContext.startForegroundService(intent)
        } catch (e: IllegalStateException) {
            val reason =
                if (
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    e.javaClass.name == FOREGROUND_SERVICE_START_NOT_ALLOWED_EXCEPTION
                ) {
                    "foreground_service_start_not_allowed"
                } else {
                    "keep_alive_start_failed"
                }
            throw KeepAliveStartupException(reason, e)
        } catch (e: SecurityException) {
            throw KeepAliveStartupException("keep_alive_start_failed", e)
        }
    }

    fun stop(context: Context) {
        val appContext = context.applicationContext
        appContext.stopService(Intent(appContext, KeepAliveService::class.java))
    }
}
