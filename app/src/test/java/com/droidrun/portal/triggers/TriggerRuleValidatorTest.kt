package com.droidrun.portal.triggers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TriggerRuleValidatorTest {
    @Test
    fun validateForSave_rejects_negative_cooldown_and_non_positive_run_limit() {
        val result = TriggerRuleValidator.validateForSave(
            TriggerRule(
                name = " Rule ",
                source = TriggerSource.USER_PRESENT,
                promptTemplate = " Prompt ",
                cooldownSeconds = -1,
                maxLaunchCount = 0,
            ),
        )

        assertFalse(result.isValid)
        assertEquals(
            "Enter zero or a positive number",
            result.firstIssueFor(TriggerRuleValidator.Field.COOLDOWN_SECONDS)?.message,
        )
        assertEquals(
            "Enter a positive number",
            result.firstIssueFor(TriggerRuleValidator.Field.MAX_LAUNCH_COUNT)?.message,
        )
    }

    @Test
    fun validateForSave_returns_sanitized_rule_for_valid_input() {
        val result = TriggerRuleValidator.validateForSave(
            TriggerRule(
                name = "  Morning Rule  ",
                source = TriggerSource.TIME_WEEKLY,
                promptTemplate = "  say hi  ",
                dailyHour = 9,
                dailyMinute = 30,
                weeklyDaysOfWeek = listOf(5, 1, 5, 9),
                cooldownSeconds = 12,
                maxLaunchCount = 3,
            ),
        )

        assertTrue(result.isValid)
        assertEquals("Morning Rule", result.rule?.name)
        assertEquals("say hi", result.rule?.promptTemplate)
        assertEquals(listOf(1, 5, 7), result.rule?.weeklyDaysOfWeek)
    }

    @Test
    fun validateForSave_rejects_nonPositive_timeDelay_beforeSanitize() {
        val zeroDelay = TriggerRuleValidator.validateForSave(
            TriggerRule(
                name = "Delay",
                source = TriggerSource.TIME_DELAY,
                promptTemplate = "hello",
                delayMinutes = 0,
            ),
        )
        val negativeDelay = TriggerRuleValidator.validateForSave(
            TriggerRule(
                name = "Delay",
                source = TriggerSource.TIME_DELAY,
                promptTemplate = "hello",
                delayMinutes = -5,
            ),
        )

        assertFalse(zeroDelay.isValid)
        assertFalse(negativeDelay.isValid)
        assertEquals(
            "Choose a delay longer than zero minutes",
            zeroDelay.firstIssueFor(TriggerRuleValidator.Field.DELAY_MINUTES)?.message,
        )
        assertEquals(
            "Choose a delay longer than zero minutes",
            negativeDelay.firstIssueFor(TriggerRuleValidator.Field.DELAY_MINUTES)?.message,
        )
    }
}
