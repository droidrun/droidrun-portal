package com.mobilerun.portal.triggers

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

class TriggerScheduler(context: Context) {

    companion object {
        const val ACTION_TRIGGER_ALARM = "com.mobilerun.portal.triggers.action.TRIGGER_ALARM"
        const val EXTRA_RULE_ID = "extra_rule_id"
    }

    private val appContext = context.applicationContext
    private val alarmManager = appContext.getSystemService(AlarmManager::class.java)

    fun rescheduleAll(rules: List<TriggerRule>, nowMs: Long = System.currentTimeMillis()) {
        rules.forEach { cancel(it.id) }
        rules.filter { it.enabled && it.isTimeRule() }.forEach { scheduleRule(it, nowMs) }
    }

    fun scheduleRule(rule: TriggerRule, nowMs: Long = System.currentTimeMillis()) {
        val triggerAt = TriggerTimeSupport.nextFireAt(rule, nowMs) ?: return
        val pendingIntent = buildPendingIntent(rule.id)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && alarmManager.canScheduleExactAlarms()) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        }
    }

    fun cancel(ruleId: String) {
        alarmManager.cancel(buildPendingIntent(ruleId))
    }

    private fun buildPendingIntent(ruleId: String): PendingIntent {
        val intent = Intent(appContext, TriggerAlarmReceiver::class.java).apply {
            action = ACTION_TRIGGER_ALARM
            putExtra(EXTRA_RULE_ID, ruleId)
        }
        return PendingIntent.getBroadcast(
            appContext,
            ruleId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
