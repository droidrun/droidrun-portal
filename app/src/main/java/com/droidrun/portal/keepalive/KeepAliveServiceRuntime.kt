package com.droidrun.portal.keepalive

import android.content.Context
import android.content.Intent

object KeepAliveServiceRuntime {
    fun start(context: Context) {
        val appContext = context.applicationContext
        val intent =
            Intent(appContext, KeepAliveService::class.java).apply {
                action = KeepAliveService.ACTION_RECONCILE
            }
        appContext.startForegroundService(intent)
    }

    fun stop(context: Context) {
        val appContext = context.applicationContext
        appContext.stopService(Intent(appContext, KeepAliveService::class.java))
    }
}
