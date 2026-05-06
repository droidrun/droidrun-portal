package com.mobilerun.portal.triggers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class TriggerBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_LOCKED_BOOT_COMPLETED) {
            return
        }
        TriggerRuntime.initialize(context)
        TriggerRuntime.onRulesChanged()
    }
}
