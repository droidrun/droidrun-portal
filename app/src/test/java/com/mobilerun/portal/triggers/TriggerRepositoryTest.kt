package com.mobilerun.portal.triggers

import android.content.Context
import android.content.SharedPreferences
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Modifier

class TriggerRepositoryTest {

    private lateinit var context: Context
    private lateinit var prefsStore: MutableMap<String, Any?>

    @Before
    fun setUp() {
        clearSingleton()
        prefsStore = mutableMapOf()
        context = mockContext(prefsStore)
    }

    @After
    fun tearDown() {
        clearSingleton()
    }

    @Test
    fun `deleteRun removes only the targeted run record`() {
        val repository = TriggerRepository.getInstance(context)
        val preservedRule = TriggerRule(
            id = "rule-1",
            name = "Battery low",
            source = TriggerSource.BATTERY_LOW,
            promptTemplate = "hello",
        )
        val deletedRun = TriggerRunRecord(
            id = "run-delete",
            ruleId = preservedRule.id,
            ruleName = preservedRule.name,
            source = preservedRule.source,
            disposition = TriggerRunDisposition.MATCHED,
            summary = "Matched battery low",
            timestampMs = 100L,
        )
        val keptRun = TriggerRunRecord(
            id = "run-keep",
            ruleId = preservedRule.id,
            ruleName = preservedRule.name,
            source = preservedRule.source,
            disposition = TriggerRunDisposition.LAUNCHED,
            summary = "Launched portal task",
            timestampMs = 200L,
        )

        repository.saveRule(preservedRule)
        repository.addRun(deletedRun)
        repository.addRun(keptRun)

        repository.deleteRun(deletedRun.id)

        assertEquals(listOf(keptRun.id), repository.listRuns().map { it.id })
        assertEquals(listOf(preservedRule.id), repository.listRules().map { it.id })
    }

    @Test
    fun `clearRuns removes run history and keeps rules`() {
        val repository = TriggerRepository.getInstance(context)
        val preservedRule = TriggerRule(
            id = "rule-1",
            name = "Battery low",
            source = TriggerSource.BATTERY_LOW,
            promptTemplate = "hello",
        )

        repository.saveRule(preservedRule)
        repository.addRun(
            TriggerRunRecord(
                id = "run-1",
                ruleId = preservedRule.id,
                ruleName = preservedRule.name,
                source = preservedRule.source,
                disposition = TriggerRunDisposition.MATCHED,
                summary = "Matched battery low",
                timestampMs = 100L,
            ),
        )

        repository.clearRuns()

        assertTrue(repository.listRuns().isEmpty())
        assertEquals(listOf(preservedRule.id), repository.listRules().map { it.id })
    }

    @Test
    fun `migration drops removed trigger sources from stored rules and runs`() {
        prefsStore["schema_version"] = 4
        prefsStore["rules_json"] = JSONArray().apply {
            put(
                JSONObject().apply {
                    put("id", "keep-rule")
                    put("enabled", true)
                    put("name", "Keep")
                    put("source", TriggerSource.NOTIFICATION_POSTED.name)
                    put("promptTemplate", "hello")
                },
            )
            put(
                JSONObject().apply {
                    put("id", "drop-rule")
                    put("enabled", true)
                    put("name", "Drop")
                    put("source", "CALL_STATE_CHANGED")
                    put("promptTemplate", "hello")
                },
            )
        }.toString()
        prefsStore["runs_json"] = JSONArray().apply {
            put(
                JSONObject().apply {
                    put("id", "keep-run")
                    put("ruleId", "keep-rule")
                    put("ruleName", "Keep")
                    put("source", TriggerSource.SMS_RECEIVED.name)
                    put("disposition", TriggerRunDisposition.MATCHED.name)
                    put("summary", "ok")
                },
            )
            put(
                JSONObject().apply {
                    put("id", "drop-run")
                    put("ruleId", "drop-rule")
                    put("ruleName", "Drop")
                    put("source", "SCREEN_ON")
                    put("disposition", TriggerRunDisposition.MATCHED.name)
                    put("summary", "drop")
                },
            )
        }.toString()

        val repository = TriggerRepository.getInstance(context)

        assertEquals(listOf("keep-rule"), repository.listRules().map { it.id })
        assertEquals(listOf("keep-run"), repository.listRuns().map { it.id })
        assertEquals(TriggerJson.CURRENT_SCHEMA_VERSION, prefsStore["schema_version"])
        assertFalse((prefsStore["rules_json"] as String).contains("CALL_STATE_CHANGED"))
        assertFalse((prefsStore["runs_json"] as String).contains("SCREEN_ON"))
    }

    private fun mockContext(store: MutableMap<String, Any?>): Context {
        val context = mockk<Context>(relaxed = true)
        val sharedPrefs = mockPreferences(store)

        every { context.applicationContext } returns context
        every {
            context.getSharedPreferences("droidrun_triggers", Context.MODE_PRIVATE)
        } returns sharedPrefs

        return context
    }

    private fun mockPreferences(store: MutableMap<String, Any?>): SharedPreferences {
        val prefs = mockk<SharedPreferences>()
        val editor = mockk<SharedPreferences.Editor>()

        every { prefs.contains(any()) } answers { store.containsKey(firstArg()) }
        every { prefs.getString(any(), any()) } answers {
            store[firstArg<String>()] as? String ?: secondArg<String?>()
        }
        every { prefs.getInt(any(), any()) } answers {
            store[firstArg<String>()] as? Int ?: secondArg<Int>()
        }
        every { prefs.edit() } returns editor

        every { editor.putString(any(), any()) } answers {
            store[firstArg()] = secondArg<String?>()
            editor
        }
        every { editor.putInt(any(), any()) } answers {
            store[firstArg()] = secondArg<Int>()
            editor
        }
        every { editor.apply() } just Runs
        every { editor.commit() } returns true

        return prefs
    }

    private fun clearSingleton() {
        val owners = listOf(TriggerRepository::class.java, TriggerRepository.Companion::class.java)
        for (owner in owners) {
            val field = owner.declaredFields.firstOrNull { it.name == "INSTANCE" } ?: continue
            field.isAccessible = true
            val receiver = if (Modifier.isStatic(field.modifiers)) null else TriggerRepository.Companion
            field.set(receiver, null)
            return
        }
        error("TriggerRepository INSTANCE field not found")
    }
}
