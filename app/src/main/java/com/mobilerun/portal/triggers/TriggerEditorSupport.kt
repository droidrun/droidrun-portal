package com.mobilerun.portal.triggers

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import com.mobilerun.portal.service.MobilerunNotificationListener

object TriggerEditorSupport {
    private const val DEFAULT_COOLDOWN_SECONDS = 60

    data class Capabilities(
        val supportsCooldown: Boolean = true,
        val supportsRunLimit: Boolean = true,
    )

    data class Visibility(
        val showMatchMode: Boolean = false,
        val showPackageName: Boolean = false,
        val showTitleFilter: Boolean = false,
        val showTextFilter: Boolean = false,
        val showThreshold: Boolean = false,
        val showNetworkType: Boolean = false,
        val showPhoneNumber: Boolean = false,
        val showMessageFilter: Boolean = false,
        val showDelay: Boolean = false,
        val showAbsoluteTime: Boolean = false,
        val showRecurringTime: Boolean = false,
        val showCooldown: Boolean = true,
        val showRunLimit: Boolean = true,
    )

    fun visibilityFor(source: TriggerSource): Visibility {
        return when (source) {
            TriggerSource.NOTIFICATION_POSTED,
            TriggerSource.NOTIFICATION_REMOVED,
            -> Visibility(
                showMatchMode = true,
                showPackageName = true,
                showTitleFilter = true,
                showTextFilter = true,
            )

            TriggerSource.APP_ENTERED,
            TriggerSource.APP_EXITED,
            -> Visibility(
                showMatchMode = true,
                showPackageName = true,
            )

            TriggerSource.BATTERY_LEVEL_CHANGED -> Visibility(showThreshold = true)

            TriggerSource.NETWORK_CONNECTED,
            TriggerSource.NETWORK_TYPE_CHANGED,
            -> Visibility(showNetworkType = true)

            TriggerSource.SMS_RECEIVED -> Visibility(
                showMatchMode = true,
                showPhoneNumber = true,
                showMessageFilter = true,
            )

            TriggerSource.TIME_DELAY -> Visibility(
                showDelay = true,
                showCooldown = false,
                showRunLimit = false,
            )
            TriggerSource.TIME_ABSOLUTE -> Visibility(
                showAbsoluteTime = true,
                showCooldown = false,
                showRunLimit = false,
            )
            TriggerSource.TIME_DAILY,
            TriggerSource.TIME_WEEKLY,
            -> Visibility(
                showRecurringTime = true,
                showCooldown = false,
            )

            // Sources that use the editor defaults on purpose.
            TriggerSource.BATTERY_LOW,
            TriggerSource.BATTERY_OKAY,
            TriggerSource.POWER_CONNECTED,
            TriggerSource.POWER_DISCONNECTED,
            TriggerSource.USER_PRESENT,
            -> Visibility()
        }
    }

    fun capabilitiesFor(source: TriggerSource): Capabilities {
        val visibility = visibilityFor(source)
        return Capabilities(
            supportsCooldown = visibility.showCooldown,
            supportsRunLimit = visibility.showRunLimit,
        )
    }

    fun defaultCooldownSecondsFor(source: TriggerSource): Int {
        return if (capabilitiesFor(source).supportsCooldown) {
            DEFAULT_COOLDOWN_SECONDS
        } else {
            0
        }
    }

    fun sanitize(rule: TriggerRule): TriggerRule {
        val visibility = visibilityFor(rule.source)
        val capabilities = capabilitiesFor(rule.source)
        val weeklyDays = rule.resolvedWeeklyDaysOfWeek()
            .takeIf { rule.source == TriggerSource.TIME_WEEKLY && it.isNotEmpty() }

        return rule.copy(
            cooldownSeconds = if (capabilities.supportsCooldown) {
                rule.cooldownSeconds.coerceAtLeast(0)
            } else {
                0
            },
            packageName = rule.packageName.takeIfVisible(visibility.showPackageName),
            titleFilter = rule.titleFilter.takeIfVisible(visibility.showTitleFilter),
            textFilter = rule.textFilter.takeIfVisible(visibility.showTextFilter),
            thresholdValue = if (visibility.showThreshold) rule.thresholdValue else null,
            networkType = if (visibility.showNetworkType) rule.networkType else null,
            phoneNumberFilter = rule.phoneNumberFilter.takeIfVisible(visibility.showPhoneNumber),
            messageFilter = rule.messageFilter.takeIfVisible(visibility.showMessageFilter),
            absoluteTimeMillis = when {
                visibility.showAbsoluteTime -> rule.absoluteTimeMillis
                visibility.showDelay -> rule.absoluteTimeMillis
                else -> null
            },
            delayMinutes = if (visibility.showDelay) rule.delayMinutes?.coerceAtLeast(1) else null,
            dailyHour = if (visibility.showRecurringTime) rule.dailyHour?.coerceIn(0, 23) else null,
            dailyMinute = if (visibility.showRecurringTime) rule.dailyMinute?.coerceIn(0, 59) else null,
            weeklyDaysOfWeek = weeklyDays,
            weeklyDayOfWeek = weeklyDays?.firstOrNull(),
            maxLaunchCount = if (capabilities.supportsRunLimit) {
                rule.maxLaunchCount?.takeIf { it > 0 }
            } else {
                null
            },
            thresholdComparison = if (visibility.showThreshold) {
                rule.thresholdComparison
            } else {
                TriggerThresholdComparison.AT_OR_BELOW
            },
            stringMatchMode = if (visibility.showMatchMode) {
                rule.stringMatchMode
            } else {
                TriggerStringMatchMode.CONTAINS
            },
        )
    }

    /**
     * True for sources that need the notification-listener permission.
     * Distinct from [TriggerRuntime.shouldDebounce]: removed notifications
     * need access too, but are never debounced.
     */
    fun requiresNotificationAccess(source: TriggerSource): Boolean {
        return source == TriggerSource.NOTIFICATION_POSTED ||
            source == TriggerSource.NOTIFICATION_REMOVED
    }

    fun isNotificationAccessEnabled(context: Context): Boolean {
        val componentName = ComponentName(context, MobilerunNotificationListener::class.java)
        val flat = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners",
        )
        return flat?.contains(componentName.flattenToString()) == true
    }

    private fun String?.takeIfVisible(visible: Boolean): String? {
        if (!visible) return null
        return this?.trim()?.takeIf { it.isNotBlank() }
    }
}
