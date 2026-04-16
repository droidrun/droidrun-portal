package com.mobilerun.portal.triggers

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object TriggerUiSupport {
    private val timestampFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

    fun sourceLabel(source: TriggerSource): String {
        return when (source) {
            TriggerSource.TIME_DELAY -> "After a delay"
            TriggerSource.TIME_ABSOLUTE -> "At a specific time"
            TriggerSource.TIME_DAILY -> "Repeat every day"
            TriggerSource.TIME_WEEKLY -> "Repeat on selected weekdays"
            else -> source.name
                .lowercase(Locale.US)
                .split('_')
                .joinToString(" ") { it.replaceFirstChar { char -> char.titlecase(Locale.US) } }
        }
    }

    fun sourceDescription(source: TriggerSource): String {
        return when (source) {
            TriggerSource.TIME_DELAY -> "Run once after the selected delay."
            TriggerSource.TIME_ABSOLUTE -> "Run once at the selected date and time."
            TriggerSource.TIME_DAILY -> "Run every day at the selected time."
            TriggerSource.TIME_WEEKLY -> "Run on the selected weekdays at the selected time."
            TriggerSource.NOTIFICATION_POSTED -> "Run when a matching notification appears."
            TriggerSource.NOTIFICATION_REMOVED -> "Run when a matching notification is dismissed or disappears."
            TriggerSource.APP_ENTERED -> "Run when the selected app comes to the foreground."
            TriggerSource.APP_EXITED -> "Run when the selected app leaves the foreground."
            TriggerSource.BATTERY_LOW -> "Run when Android reports that the battery is low."
            TriggerSource.BATTERY_OKAY -> "Run when Android reports that the battery is okay again."
            TriggerSource.BATTERY_LEVEL_CHANGED -> "Run when the battery level crosses the selected threshold."
            TriggerSource.POWER_CONNECTED -> "Run when charging starts."
            TriggerSource.POWER_DISCONNECTED -> "Run when charging stops."
            TriggerSource.USER_PRESENT -> "Run when the device is unlocked and the user becomes active."
            TriggerSource.NETWORK_CONNECTED -> "Run when the device gains network connectivity."
            TriggerSource.NETWORK_TYPE_CHANGED -> "Run when the connection type changes, such as Wi-Fi to cellular."
            TriggerSource.SMS_RECEIVED -> "Run when a matching SMS arrives."
        }
    }

    fun summary(rule: TriggerRule): String {
        return buildList {
            add(sourceLabel(rule.source))
            rule.packageName?.takeIf { it.isNotBlank() }?.let { add("pkg=$it") }
            rule.titleFilter?.takeIf { it.isNotBlank() }?.let { add("title=$it") }
            rule.textFilter?.takeIf { it.isNotBlank() }?.let { add("text=$it") }
            rule.networkType?.let { add("network=${it.name}") }
            rule.thresholdValue?.let { add("threshold=${rule.thresholdComparison.name.lowercase()} $it") }
            rule.phoneNumberFilter?.takeIf { it.isNotBlank() }?.let { add("phone=$it") }
            rule.delayMinutes?.let { add("delay=${it}m") }
            if (rule.source == TriggerSource.TIME_ABSOLUTE) {
                rule.absoluteTimeMillis?.let { add("at=${formatTimestamp(it)}") }
            }
            if (rule.source == TriggerSource.TIME_DAILY || rule.source == TriggerSource.TIME_WEEKLY) {
                val hour = rule.dailyHour ?: 0
                val minute = rule.dailyMinute ?: 0
                add(String.format(Locale.US, "%02d:%02d", hour, minute))
                if (rule.source == TriggerSource.TIME_WEEKLY) {
                    val labels = rule.resolvedWeeklyDaysOfWeek()
                        .mapNotNull { dayOfWeekLabel(it) }
                    if (labels.isNotEmpty()) {
                        add(labels.joinToString(", "))
                    }
                }
            }
        }.joinToString(" • ")
    }

    fun dispositionLabel(disposition: TriggerRunDisposition): String {
        return disposition.name
            .lowercase(Locale.US)
            .split('_')
            .joinToString(" ") { it.replaceFirstChar { char -> char.titlecase(Locale.US) } }
    }

    fun formatTimestamp(timestampMs: Long): String {
        return timestampFormatter.format(Date(timestampMs))
    }

    fun dayOfWeekEntries(): List<Pair<String, Int>> {
        return listOf(
            "Sunday" to java.util.Calendar.SUNDAY,
            "Monday" to java.util.Calendar.MONDAY,
            "Tuesday" to java.util.Calendar.TUESDAY,
            "Wednesday" to java.util.Calendar.WEDNESDAY,
            "Thursday" to java.util.Calendar.THURSDAY,
            "Friday" to java.util.Calendar.FRIDAY,
            "Saturday" to java.util.Calendar.SATURDAY,
        )
    }

    fun dayOfWeekLabel(dayOfWeek: Int): String? {
        return dayOfWeekEntries().firstOrNull { it.second == dayOfWeek }?.first
    }
}
