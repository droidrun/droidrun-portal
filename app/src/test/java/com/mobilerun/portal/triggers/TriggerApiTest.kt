package com.mobilerun.portal.triggers

import android.content.Context
import io.mockk.mockk
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TriggerApiTest {
    @Test
    fun catalog_includes_current_enums_and_source_metadata() {
        val api = TriggerApi(
            context = mockk<Context>(relaxed = true),
            operations = FakeTriggerOperations(),
            environmentStatusProvider = TriggerEnvironmentStatusProvider { _ ->
                TriggerEnvironmentStatus(false, false, false, false, true)
            },
        )

        val catalog = api.catalog()
        assertEquals(TriggerJson.CURRENT_SCHEMA_VERSION, catalog.getInt("schemaVersion"))
        assertEquals(EventTypeNames.current(), jsonArrayValues(catalog.getJSONArray("eventTypes")))
        assertEquals(TriggerSource.entries.map { it.name }, jsonArrayValues(catalog.getJSONArray("triggerSources")))
        assertEquals(
            TriggerRunDisposition.entries.map { it.name },
            jsonArrayValues(catalog.getJSONArray("runDispositions")),
        )
        assertEquals(
            TriggerStringMatchMode.entries.map { it.name },
            jsonArrayValues(catalog.getJSONArray("stringMatchModes")),
        )
        assertEquals(
            TriggerNetworkType.entries.map { it.name },
            jsonArrayValues(catalog.getJSONArray("networkTypes")),
        )
        assertEquals(
            TriggerThresholdComparison.entries.map { it.name },
            jsonArrayValues(catalog.getJSONArray("thresholdComparisons")),
        )

        val sourceMetadata = catalog.getJSONObject("sourceMetadata")
        assertEquals(TriggerSource.entries.size, sourceMetadata.length())
        val notificationPosted = sourceMetadata.getJSONObject(TriggerSource.NOTIFICATION_POSTED.name)
        assertEquals(
            TriggerUiSupport.sourceDescription(TriggerSource.NOTIFICATION_POSTED),
            notificationPosted.getString("description"),
        )
        assertTrue(notificationPosted.getJSONObject("visibility").getBoolean("showTitleFilter"))
        assertTrue(notificationPosted.getJSONObject("capabilities").getBoolean("supportsCooldown"))
    }

    @Test
    fun status_reflects_environment_and_counts() {
        val operations = FakeTriggerOperations(
            rules = mutableListOf(
                TriggerRule(name = "one", source = TriggerSource.USER_PRESENT, promptTemplate = "a"),
                TriggerRule(name = "two", source = TriggerSource.BATTERY_LOW, promptTemplate = "b"),
            ),
            runs = mutableListOf(
                TriggerRunRecord(
                    ruleId = "rule-1",
                    ruleName = "one",
                    source = TriggerSource.USER_PRESENT,
                    disposition = TriggerRunDisposition.MATCHED,
                    summary = "matched",
                ),
            ),
        )
        val api = TriggerApi(
            context = mockk<Context>(relaxed = true),
            operations = operations,
            environmentStatusProvider = TriggerEnvironmentStatusProvider { _ ->
                TriggerEnvironmentStatus(
                    accessibilityServiceConnected = false,
                    notificationAccessEnabled = true,
                    receiveSmsGranted = true,
                    readContactsGranted = false,
                    exactAlarmAvailable = true,
                )
            },
        )

        val status = api.status()
        assertFalse(status.getBoolean("accessibilityServiceConnected"))
        assertTrue(status.getBoolean("notificationAccessEnabled"))
        assertTrue(status.getBoolean("receiveSmsGranted"))
        assertFalse(status.getBoolean("readContactsGranted"))
        assertTrue(status.getBoolean("exactAlarmAvailable"))
        assertEquals(2, status.getInt("ruleCount"))
        assertEquals(1, status.getInt("runCount"))
        assertEquals(TriggerJson.CURRENT_SCHEMA_VERSION, status.getInt("schemaVersion"))
    }

    @Test
    fun saveRule_rejects_invalid_rules_and_persists_valid_rules() {
        val operations = FakeTriggerOperations()
        val api = TriggerApi(
            context = mockk<Context>(relaxed = true),
            operations = operations,
            environmentStatusProvider = TriggerEnvironmentStatusProvider { _ ->
                TriggerEnvironmentStatus(false, false, false, false, true)
            },
            nowProvider = { 1_000L },
        )

        val invalid = api.saveRule(
            JSONObject().apply {
                put("name", "Invalid")
                put("source", TriggerSource.TIME_ABSOLUTE.name)
                put("promptTemplate", "hello")
                put("absoluteTimeMillis", 999L)
            }.toString(),
        )
        assertEquals(
            TriggerApiResult.Error("Choose a future date and time"),
            invalid,
        )

        val invalidCooldown = api.saveRule(
            JSONObject().apply {
                put("name", "Invalid Cooldown")
                put("source", TriggerSource.USER_PRESENT.name)
                put("promptTemplate", "hello")
                put("cooldownSeconds", -1)
            }.toString(),
        )
        assertEquals(
            TriggerApiResult.Error("Enter zero or a positive number"),
            invalidCooldown,
        )

        val invalidRunLimit = api.saveRule(
            JSONObject().apply {
                put("name", "Invalid Limit")
                put("source", TriggerSource.USER_PRESENT.name)
                put("promptTemplate", "hello")
                put("maxLaunchCount", 0)
            }.toString(),
        )
        assertEquals(
            TriggerApiResult.Error("Enter a positive number"),
            invalidRunLimit,
        )

        val invalidDelay = api.saveRule(
            JSONObject().apply {
                put("name", "Invalid Delay")
                put("source", TriggerSource.TIME_DELAY.name)
                put("promptTemplate", "hello")
                put("delayMinutes", 0)
            }.toString(),
        )
        assertEquals(
            TriggerApiResult.Error("Choose a delay longer than zero minutes"),
            invalidDelay,
        )

        val invalidNegativeDelay = api.saveRule(
            JSONObject().apply {
                put("name", "Invalid Negative Delay")
                put("source", TriggerSource.TIME_DELAY.name)
                put("promptTemplate", "hello")
                put("delayMinutes", -5)
            }.toString(),
        )
        assertEquals(
            TriggerApiResult.Error("Choose a delay longer than zero minutes"),
            invalidNegativeDelay,
        )

        val valid = api.saveRule(
            JSONObject().apply {
                put("id", "rule-1")
                put("name", "  Saved Rule  ")
                put("source", TriggerSource.USER_PRESENT.name)
                put("promptTemplate", "  hello  ")
            }.toString(),
        )

        assertTrue(valid is TriggerApiResult.Success)
        val saved = (valid as TriggerApiResult.Success).value
        assertEquals("rule-1", saved.getString("id"))
        assertEquals("Saved Rule", saved.getString("name"))
        assertEquals("hello", saved.getString("promptTemplate"))
        assertEquals(listOf("rule-1"), operations.listRules().map { it.id })
    }

    @Test
    fun documented_contract_lists_match_live_enums() {
        assertEquals(
            listOf(
                "NOTIFICATION",
                "NOTIFICATION_POSTED",
                "NOTIFICATION_REMOVED",
                "APP_ENTERED",
                "APP_EXITED",
                "BATTERY_LOW",
                "BATTERY_OKAY",
                "BATTERY_LEVEL_CHANGED",
                "POWER_CONNECTED",
                "POWER_DISCONNECTED",
                "USER_PRESENT",
                "NETWORK_CONNECTED",
                "NETWORK_TYPE_CHANGED",
                "SMS_RECEIVED",
                "PING",
                "PONG",
                "UNKNOWN",
            ),
            EventTypeNames.current(),
        )
        assertEquals(
            listOf(
                "TIME_DELAY",
                "TIME_ABSOLUTE",
                "TIME_DAILY",
                "TIME_WEEKLY",
                "NOTIFICATION_POSTED",
                "NOTIFICATION_REMOVED",
                "APP_ENTERED",
                "APP_EXITED",
                "BATTERY_LOW",
                "BATTERY_OKAY",
                "BATTERY_LEVEL_CHANGED",
                "POWER_CONNECTED",
                "POWER_DISCONNECTED",
                "USER_PRESENT",
                "NETWORK_CONNECTED",
                "NETWORK_TYPE_CHANGED",
                "SMS_RECEIVED",
            ),
            TriggerSource.entries.map { it.name },
        )
        assertEquals(
            listOf(
                "MATCHED",
                "LAUNCHED",
                "SKIPPED_BUSY",
                "BUFFERED",
                "DEBOUNCED",
                "PERMISSION_MISSING",
                "LAUNCH_FAILED",
                "RULE_DISABLED",
                "TEST_LAUNCHED",
            ),
            TriggerRunDisposition.entries.map { it.name },
        )
        assertEquals(
            listOf("SKIP", "QUEUE"),
            TriggerBusyPolicy.entries.map { it.name },
        )
        assertEquals(
            listOf("CONTAINS", "REGEX"),
            TriggerStringMatchMode.entries.map { it.name },
        )
        assertEquals(
            listOf("NONE", "WIFI", "CELLULAR", "ETHERNET", "OTHER"),
            TriggerNetworkType.entries.map { it.name },
        )
        assertEquals(
            listOf("AT_OR_BELOW", "AT_OR_ABOVE"),
            TriggerThresholdComparison.entries.map { it.name },
        )
    }

    private fun jsonArrayValues(array: JSONArray): List<String> {
        return List(array.length()) { index -> array.getString(index) }
    }

    private class FakeTriggerOperations(
        private val rules: MutableList<TriggerRule> = mutableListOf(),
        private val runs: MutableList<TriggerRunRecord> = mutableListOf(),
    ) : TriggerOperations {
        override fun listRules(): List<TriggerRule> = rules.toList()

        override fun getRule(ruleId: String): TriggerRule? = rules.firstOrNull { it.id == ruleId }

        override fun saveRule(rule: TriggerRule) {
            rules.removeAll { it.id == rule.id }
            rules += rule
        }

        override fun deleteRule(ruleId: String) {
            rules.removeAll { it.id == ruleId }
        }

        override fun setRuleEnabled(ruleId: String, enabled: Boolean) {
            val index = rules.indexOfFirst { it.id == ruleId }
            if (index >= 0) {
                rules[index] = rules[index].copy(enabled = enabled)
            }
        }

        override fun launchTest(ruleId: String) = Unit

        override fun listRuns(limit: Int): List<TriggerRunRecord> = runs.take(limit)

        override fun deleteRun(runId: String) {
            runs.removeAll { it.id == runId }
        }

        override fun clearRuns() {
            runs.clear()
        }
    }

    private object EventTypeNames {
        fun current(): List<String> = com.mobilerun.portal.events.model.EventType.entries.map { it.name }
    }
}
