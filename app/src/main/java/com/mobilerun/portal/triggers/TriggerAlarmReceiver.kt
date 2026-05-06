package com.mobilerun.portal.triggers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class TriggerAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != TriggerScheduler.ACTION_TRIGGER_ALARM) return
        val ruleId = intent.getStringExtra(TriggerScheduler.EXTRA_RULE_ID)?.takeIf { it.isNotBlank() }
            ?: return
        TriggerRuntime.initialize(context)
        TriggerRuntime.handleScheduledRule(ruleId)
    }
}
