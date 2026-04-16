package com.mobilerun.portal.triggers

import com.mobilerun.portal.taskprompt.PortalTaskSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject
import java.util.Calendar

class TriggerCoreTest {
    @Test
    fun `template renderer injects placeholders without appending context`() {
        val rendered = TriggerTemplateRenderer.render(
            promptTemplate = "Handle {{trigger.package}} from {{trigger.title}} at {{trigger.timestamp}}",
            signal = TriggerSignal(
                source = TriggerSource.NOTIFICATION_POSTED,
                timestampMs = 1234L,
                payload = mapOf(
                    "package" to "com.whatsapp",
                    "title" to "John",
                ),
            ),
        )

        assertTrue(rendered.contains("Handle com.whatsapp from John at 1234"))
        assertFalse(rendered.contains("Trigger context"))
        assertFalse(rendered.contains("\"type\":"))
    }

    @Test
    fun `matcher supports contains filters`() {
        val rule = TriggerRule(
            name = "WhatsApp messages",
            source = TriggerSource.NOTIFICATION_POSTED,
            promptTemplate = "x",
            packageName = "com.whatsapp",
            titleFilter = "John",
            textFilter = "hello",
        )

        val signal = TriggerSignal(
            source = TriggerSource.NOTIFICATION_POSTED,
            payload = mapOf(
                "package" to "com.whatsapp",
                "title" to "John Smith",
                "text" to "Hello from mobile",
            ),
        )

        assertTrue(TriggerMatcher.matches(rule, signal))
    }

    @Test
    fun `matcher supports regex and threshold comparisons`() {
        val regexRule = TriggerRule(
            name = "Regex SMS",
            source = TriggerSource.SMS_RECEIVED,
            promptTemplate = "x",
            stringMatchMode = TriggerStringMatchMode.REGEX,
            phoneNumberFilter = """\+1555\d+""",
            messageFilter = "urgent|asap",
        )
        val batteryRule = TriggerRule(
            name = "Battery high",
            source = TriggerSource.BATTERY_LEVEL_CHANGED,
            promptTemplate = "x",
            thresholdValue = 80,
            thresholdComparison = TriggerThresholdComparison.AT_OR_ABOVE,
        )

        assertTrue(
            TriggerMatcher.matches(
                regexRule,
                TriggerSignal(
                    source = TriggerSource.SMS_RECEIVED,
                    payload = mapOf(
                        "phone_number" to "+15551234567",
                        "message" to "please do this asap",
                    ),
                ),
            ),
        )
        assertTrue(
            TriggerMatcher.matches(
                batteryRule,
                TriggerSignal(
                    source = TriggerSource.BATTERY_LEVEL_CHANGED,
                    payload = mapOf("battery_level" to "81"),
                ),
            ),
        )
        assertFalse(
            TriggerMatcher.matches(
                batteryRule,
                TriggerSignal(
                    source = TriggerSource.BATTERY_LEVEL_CHANGED,
                    payload = mapOf("battery_level" to "40"),
                ),
            ),
        )
    }

    @Test
    fun `time support returns future values`() {
        val now = Calendar.getInstance().apply {
            set(2026, Calendar.MARCH, 11, 10, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val daily = TriggerRule(
            name = "Daily",
            source = TriggerSource.TIME_DAILY,
            promptTemplate = "x",
            dailyHour = 11,
            dailyMinute = 30,
        )
        val weekly = TriggerRule(
            name = "Weekly",
            source = TriggerSource.TIME_WEEKLY,
            promptTemplate = "x",
            weeklyDaysOfWeek = listOf(Calendar.THURSDAY, Calendar.FRIDAY),
            dailyHour = 9,
            dailyMinute = 15,
        )

        val dailyNext = TriggerTimeSupport.nextFireAt(daily, now)
        val weeklyNext = TriggerTimeSupport.nextFireAt(weekly, now)

        assertNotNull(dailyNext)
        assertNotNull(weeklyNext)
        assertTrue(dailyNext!! > now)
        assertTrue(weeklyNext!! > now)
        assertEquals(
            Calendar.getInstance().apply {
                timeInMillis = weeklyNext
            }.get(Calendar.DAY_OF_WEEK),
            Calendar.THURSDAY,
        )
    }

    @Test
    fun `json roundtrip preserves trigger rule fields`() {
        val original = TriggerRule(
            name = "Roundtrip",
            source = TriggerSource.TIME_WEEKLY,
            promptTemplate = "hello",
            cooldownSeconds = 90,
            packageName = "com.example",
            weeklyDaysOfWeek = listOf(Calendar.FRIDAY, Calendar.SATURDAY),
            dailyHour = 8,
            dailyMinute = 45,
            maxLaunchCount = 3,
            successfulLaunchCount = 1,
            returnToPortal = true,
            taskSettingsOverride = PortalTaskSettings(
                llmModel = "openai/gpt-5.1",
                reasoning = true,
                vision = false,
                maxSteps = 123,
                temperature = 0.7,
                executionTimeout = 456,
            ),
        )

        val parsed = TriggerJson.ruleFromJson(TriggerJson.ruleToJson(original))

        assertEquals(original.name, parsed.name)
        assertEquals(original.source, parsed.source)
        assertEquals(original.packageName, parsed.packageName)
        assertEquals(original.resolvedWeeklyDaysOfWeek(), parsed.resolvedWeeklyDaysOfWeek())
        assertEquals(original.maxLaunchCount, parsed.maxLaunchCount)
        assertEquals(original.successfulLaunchCount, parsed.successfulLaunchCount)
        assertEquals(original.returnToPortal, parsed.returnToPortal)
        assertEquals(original.taskSettingsOverride, parsed.taskSettingsOverride)
    }

    @Test
    fun `json migration maps legacy weekly day to weekly day list`() {
        val legacy = JSONObject().apply {
            put("id", "rule-1")
            put("enabled", true)
            put("name", "Legacy weekly")
            put("source", TriggerSource.TIME_WEEKLY.name)
            put("promptTemplate", "hello")
            put("dailyHour", 7)
            put("dailyMinute", 10)
            put("weeklyDayOfWeek", Calendar.MONDAY)
        }

        val parsed = TriggerJson.ruleFromJson(legacy)
        val rewritten = TriggerJson.ruleToJson(parsed)

        assertEquals(listOf(Calendar.MONDAY), parsed.resolvedWeeklyDaysOfWeek())
        assertTrue(rewritten.has("weeklyDaysOfWeek"))
        assertFalse(rewritten.has("weeklyDayOfWeek"))
        assertEquals(null, parsed.maxLaunchCount)
        assertEquals(0, parsed.successfulLaunchCount)
        assertFalse(parsed.returnToPortal)
    }

    @Test
    fun `json migration drops removed trigger sources from rules and runs`() {
        val parsedRules = TriggerJson.parseRules(
            """
            [
              {"id":"keep-rule","enabled":true,"name":"Keep","source":"NOTIFICATION_POSTED","promptTemplate":"hello"},
              {"id":"drop-rule","enabled":true,"name":"Drop","source":"CALL_STATE_CHANGED","promptTemplate":"hello"}
            ]
            """.trimIndent(),
        )
        val parsedRuns = TriggerJson.parseRuns(
            """
            [
              {"id":"keep-run","ruleId":"keep-rule","ruleName":"Keep","source":"SMS_RECEIVED","disposition":"MATCHED","summary":"ok"},
              {"id":"drop-run","ruleId":"drop-rule","ruleName":"Drop","source":"SCREEN_ON","disposition":"MATCHED","summary":"drop"}
            ]
            """.trimIndent(),
        )

        assertEquals(listOf("keep-rule"), parsedRules.map { it.id })
        assertEquals(listOf("keep-run"), parsedRuns.map { it.id })
    }

    @Test
    fun `source descriptions match remaining trigger sources`() {
        val expected = mapOf(
            TriggerSource.TIME_DELAY to "Run once after the selected delay.",
            TriggerSource.TIME_ABSOLUTE to "Run once at the selected date and time.",
            TriggerSource.TIME_DAILY to "Run every day at the selected time.",
            TriggerSource.TIME_WEEKLY to "Run on the selected weekdays at the selected time.",
            TriggerSource.NOTIFICATION_POSTED to "Run when a matching notification appears.",
            TriggerSource.NOTIFICATION_REMOVED to "Run when a matching notification is dismissed or disappears.",
            TriggerSource.APP_ENTERED to "Run when the selected app comes to the foreground.",
            TriggerSource.APP_EXITED to "Run when the selected app leaves the foreground.",
            TriggerSource.BATTERY_LOW to "Run when Android reports that the battery is low.",
            TriggerSource.BATTERY_OKAY to "Run when Android reports that the battery is okay again.",
            TriggerSource.BATTERY_LEVEL_CHANGED to "Run when the battery level crosses the selected threshold.",
            TriggerSource.POWER_CONNECTED to "Run when charging starts.",
            TriggerSource.POWER_DISCONNECTED to "Run when charging stops.",
            TriggerSource.USER_PRESENT to "Run when the device is unlocked and the user becomes active.",
            TriggerSource.NETWORK_CONNECTED to "Run when the device gains network connectivity.",
            TriggerSource.NETWORK_TYPE_CHANGED to "Run when the connection type changes, such as Wi-Fi to cellular.",
            TriggerSource.SMS_RECEIVED to "Run when a matching SMS arrives.",
        )

        assertEquals(expected.keys, TriggerSource.entries.toSet())
        expected.forEach { (source, description) ->
            assertEquals(description, TriggerUiSupport.sourceDescription(source))
        }
    }

    @Test
    fun `editor support clears stale fields for battery triggers`() {
        val sanitized = TriggerEditorSupport.sanitize(
            TriggerRule(
                name = "Battery low",
                source = TriggerSource.BATTERY_LOW,
                promptTemplate = "hello",
                packageName = "com.whatsapp",
                titleFilter = "John",
                textFilter = "urgent",
                phoneNumberFilter = "+1555",
                delayMinutes = 15,
                dailyHour = 8,
                weeklyDaysOfWeek = listOf(Calendar.MONDAY),
            ),
        )

        assertEquals(null, sanitized.packageName)
        assertEquals(null, sanitized.titleFilter)
        assertEquals(null, sanitized.textFilter)
        assertEquals(null, sanitized.phoneNumberFilter)
        assertEquals(null, sanitized.delayMinutes)
        assertEquals(null, sanitized.dailyHour)
        assertTrue(sanitized.resolvedWeeklyDaysOfWeek().isEmpty())
    }

    @Test
    fun `editor support clears cooldown and run limit for one shot time triggers`() {
        val sanitized = TriggerEditorSupport.sanitize(
            TriggerRule(
                name = "One shot",
                source = TriggerSource.TIME_ABSOLUTE,
                promptTemplate = "hello",
                cooldownSeconds = 60,
                maxLaunchCount = 3,
                absoluteTimeMillis = 1234L,
            ),
        )

        assertEquals(0, sanitized.cooldownSeconds)
        assertEquals(null, sanitized.maxLaunchCount)
        assertEquals(1234L, sanitized.absoluteTimeMillis)
    }

    @Test
    fun `editor support keeps run limit and clears cooldown for recurring time triggers`() {
        val sanitized = TriggerEditorSupport.sanitize(
            TriggerRule(
                name = "Recurring",
                source = TriggerSource.TIME_WEEKLY,
                promptTemplate = "hello",
                cooldownSeconds = 60,
                maxLaunchCount = 2,
                dailyHour = 7,
                dailyMinute = 30,
                weeklyDaysOfWeek = listOf(Calendar.MONDAY),
            ),
        )

        assertEquals(0, sanitized.cooldownSeconds)
        assertEquals(2, sanitized.maxLaunchCount)
        assertEquals(listOf(Calendar.MONDAY), sanitized.resolvedWeeklyDaysOfWeek())
    }

    @Test
    fun `editor support uses 60 second default cooldown only for supported sources`() {
        assertEquals(
            60,
            TriggerEditorSupport.defaultCooldownSecondsFor(TriggerSource.NOTIFICATION_POSTED),
        )
        assertEquals(
            60,
            TriggerEditorSupport.defaultCooldownSecondsFor(TriggerSource.BATTERY_LOW),
        )
        assertEquals(
            0,
            TriggerEditorSupport.defaultCooldownSecondsFor(TriggerSource.TIME_ABSOLUTE),
        )
        assertEquals(
            0,
            TriggerEditorSupport.defaultCooldownSecondsFor(TriggerSource.TIME_WEEKLY),
        )
    }

    @Test
    fun `editor support preserves explicit zero cooldown for supported sources`() {
        val sanitized = TriggerEditorSupport.sanitize(
            TriggerRule(
                name = "No cooldown",
                source = TriggerSource.NOTIFICATION_POSTED,
                promptTemplate = "hello",
                cooldownSeconds = 0,
            ),
        )

        assertEquals(0, sanitized.cooldownSeconds)
    }
}
