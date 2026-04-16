package com.mobilerun.portal.triggers

import java.util.Calendar

object TriggerTimeSupport {
    fun nextFireAt(rule: TriggerRule, nowMs: Long): Long? {
        return when (rule.source) {
            TriggerSource.TIME_DELAY -> computeDelayTime(rule, nowMs)
            TriggerSource.TIME_ABSOLUTE -> rule.absoluteTimeMillis?.takeIf { it > nowMs }
            TriggerSource.TIME_DAILY -> computeDailyTime(rule, nowMs)
            TriggerSource.TIME_WEEKLY -> computeWeeklyTime(rule, nowMs)
            else -> null
        }
    }

    fun shouldDisableAfterFire(rule: TriggerRule): Boolean {
        return rule.source == TriggerSource.TIME_DELAY || rule.source == TriggerSource.TIME_ABSOLUTE
    }

    private fun computeDelayTime(rule: TriggerRule, nowMs: Long): Long? {
        rule.absoluteTimeMillis?.let { absolute ->
            if (absolute > nowMs) return absolute
        }
        val delayMinutes = rule.delayMinutes ?: return null
        return rule.createdAtMs + delayMinutes.coerceAtLeast(1) * 60_000L
    }

    private fun computeDailyTime(rule: TriggerRule, nowMs: Long): Long? {
        val hour = rule.dailyHour ?: return null
        val minute = rule.dailyMinute ?: return null
        val calendar = Calendar.getInstance().apply {
            timeInMillis = nowMs
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            set(Calendar.HOUR_OF_DAY, hour.coerceIn(0, 23))
            set(Calendar.MINUTE, minute.coerceIn(0, 59))
        }
        if (calendar.timeInMillis <= nowMs) {
            calendar.add(Calendar.DATE, 1)
        }
        return calendar.timeInMillis
    }

    private fun computeWeeklyTime(rule: TriggerRule, nowMs: Long): Long? {
        val dayOfWeekSet = rule.resolvedWeeklyDaysOfWeek().toSet()
        if (dayOfWeekSet.isEmpty()) return null
        val hour = rule.dailyHour ?: return null
        val minute = rule.dailyMinute ?: return null
        val calendar = Calendar.getInstance().apply {
            timeInMillis = nowMs
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            set(Calendar.HOUR_OF_DAY, hour.coerceIn(0, 23))
            set(Calendar.MINUTE, minute.coerceIn(0, 59))
        }

        while (calendar.get(Calendar.DAY_OF_WEEK) !in dayOfWeekSet ||
            calendar.timeInMillis <= nowMs
        ) {
            calendar.add(Calendar.DATE, 1)
        }

        return calendar.timeInMillis
    }
}
