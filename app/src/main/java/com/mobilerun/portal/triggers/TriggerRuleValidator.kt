package com.mobilerun.portal.triggers

object TriggerRuleValidator {
    enum class Field {
        NAME,
        PROMPT_TEMPLATE,
        COOLDOWN_SECONDS,
        THRESHOLD_VALUE,
        DELAY_MINUTES,
        ABSOLUTE_TIME,
        RECURRING_TIME,
        WEEKLY_DAYS,
        MAX_LAUNCH_COUNT,
    }

    data class Issue(
        val field: Field,
        val message: String,
    )

    data class Result(
        val rule: TriggerRule?,
        val issues: List<Issue>,
    ) {
        val isValid: Boolean
            get() = rule != null && issues.isEmpty()

        fun firstIssueFor(field: Field): Issue? = issues.firstOrNull { it.field == field }

        fun firstMessage(): String? = issues.firstOrNull()?.message
    }

    fun validateForSave(
        rule: TriggerRule,
        nowMs: Long = System.currentTimeMillis(),
    ): Result {
        val capabilities = TriggerEditorSupport.capabilitiesFor(rule.source)
        val trimmedName = rule.name.trim()
        val trimmedPromptTemplate = rule.promptTemplate.trim()
        val preparedRule = rule.copy(
            name = trimmedName,
            promptTemplate = trimmedPromptTemplate,
        )
        val issues = mutableListOf<Issue>()

        if (trimmedName.isBlank()) {
            issues += Issue(Field.NAME, "Required")
        }
        if (trimmedPromptTemplate.isBlank()) {
            issues += Issue(Field.PROMPT_TEMPLATE, "Required")
        }
        if (capabilities.supportsCooldown && rule.cooldownSeconds < 0) {
            issues += Issue(Field.COOLDOWN_SECONDS, "Enter zero or a positive number")
        }
        if (capabilities.supportsRunLimit && rule.maxLaunchCount != null && rule.maxLaunchCount <= 0) {
            issues += Issue(Field.MAX_LAUNCH_COUNT, "Enter a positive number")
        }

        when (preparedRule.source) {
            TriggerSource.BATTERY_LEVEL_CHANGED -> {
                if (preparedRule.thresholdValue == null || preparedRule.thresholdValue !in 0..100) {
                    issues += Issue(Field.THRESHOLD_VALUE, "Enter a battery level between 0 and 100")
                }
            }

            TriggerSource.TIME_DELAY -> {
                if (preparedRule.delayMinutes == null || preparedRule.delayMinutes <= 0) {
                    issues += Issue(Field.DELAY_MINUTES, "Choose a delay longer than zero minutes")
                }
            }

            TriggerSource.TIME_ABSOLUTE -> {
                when {
                    preparedRule.absoluteTimeMillis == null ->
                        issues += Issue(Field.ABSOLUTE_TIME, "Choose both a date and a time")

                    preparedRule.absoluteTimeMillis <= nowMs ->
                        issues += Issue(Field.ABSOLUTE_TIME, "Choose a future date and time")
                }
            }

            TriggerSource.TIME_DAILY,
            TriggerSource.TIME_WEEKLY,
            -> {
                if (preparedRule.dailyHour == null || preparedRule.dailyMinute == null) {
                    issues += Issue(Field.RECURRING_TIME, "Choose a recurring time")
                }
                if (
                    preparedRule.source == TriggerSource.TIME_WEEKLY &&
                    preparedRule.resolvedWeeklyDaysOfWeek().isEmpty()
                ) {
                    issues += Issue(Field.WEEKLY_DAYS, "Choose at least one weekday")
                }
            }

            else -> Unit
        }

        return if (issues.isEmpty()) {
            Result(TriggerEditorSupport.sanitize(preparedRule), emptyList())
        } else {
            Result(null, issues)
        }
    }
}
