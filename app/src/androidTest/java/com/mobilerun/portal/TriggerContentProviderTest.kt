package com.mobilerun.portal

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TriggerContentProviderTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        clearTriggerPrefs()
    }

    @After
    fun tearDown() {
        clearTriggerPrefs()
    }

    @Test
    fun catalog_and_status_queries_work_without_accessibility_service() {
        val catalog = queryJson("content://com.mobilerun.portal/triggers/catalog")
        assertEquals("success", catalog.getString("status"))
        assertTrue(catalog.getJSONObject("result").getJSONArray("eventTypes").length() > 0)

        val status = queryJson("content://com.mobilerun.portal/triggers/status")
        assertEquals("success", status.getString("status"))
        assertTrue(status.getJSONObject("result").has("accessibilityServiceConnected"))
        assertTrue(status.getJSONObject("result").has("schemaVersion"))
    }

    @Test
    fun rule_mutations_round_trip_through_provider() {
        val ruleId = "provider-rule"
        val saveResult = insert(
            "content://com.mobilerun.portal/triggers/rules/save",
            ContentValues().apply {
                put("rule_json_base64", encodeRuleJson(ruleId, "Provider Rule"))
            },
        )
        assertEquals("success", saveResult.getQueryParameter("status"))

        val rules = queryJson("content://com.mobilerun.portal/triggers/rules").getJSONArray("result")
        assertTrue(rules.length() >= 1)

        val fetched = queryJson("content://com.mobilerun.portal/triggers/rules/$ruleId")
            .getJSONObject("result")
        assertEquals(ruleId, fetched.getString("id"))

        val toggleResult = insert(
            "content://com.mobilerun.portal/triggers/rules/set_enabled",
            ContentValues().apply {
                put("rule_id", ruleId)
                put("enabled", false)
            },
        )
        assertEquals("success", toggleResult.getQueryParameter("status"))

        val updated = queryJson("content://com.mobilerun.portal/triggers/rules/$ruleId")
            .getJSONObject("result")
        assertEquals(false, updated.getBoolean("enabled"))

        val testResult = insert(
            "content://com.mobilerun.portal/triggers/rules/test",
            ContentValues().apply { put("rule_id", ruleId) },
        )
        assertEquals("success", testResult.getQueryParameter("status"))

        val deleteResult = insert(
            "content://com.mobilerun.portal/triggers/rules/delete",
            ContentValues().apply { put("rule_id", ruleId) },
        )
        assertEquals("success", deleteResult.getQueryParameter("status"))
    }

    @Test
    fun runs_delete_and_clear_work_through_provider() {
        val ruleId = "run-rule"
        insert(
            "content://com.mobilerun.portal/triggers/rules/save",
            ContentValues().apply {
                put("rule_json_base64", encodeRuleJson(ruleId, "Run Rule"))
            },
        )
        insert(
            "content://com.mobilerun.portal/triggers/rules/test",
            ContentValues().apply { put("rule_id", ruleId) },
        )

        Thread.sleep(250)

        val runs = queryJson("content://com.mobilerun.portal/triggers/runs?limit=10")
            .getJSONArray("result")
        assertTrue(runs.length() >= 1)
        val runId = runs.getJSONObject(0).getString("id")

        val deleteRunResult = insert(
            "content://com.mobilerun.portal/triggers/runs/delete",
            ContentValues().apply { put("run_id", runId) },
        )
        assertEquals("success", deleteRunResult.getQueryParameter("status"))

        val clearRunsResult = insert(
            "content://com.mobilerun.portal/triggers/runs/clear",
            ContentValues(),
        )
        assertEquals("success", clearRunsResult.getQueryParameter("status"))

        val remainingRuns = queryJson("content://com.mobilerun.portal/triggers/runs?limit=10")
            .getJSONArray("result")
        assertEquals(0, remainingRuns.length())
    }

    private fun clearTriggerPrefs() {
        context.getSharedPreferences("droidrun_triggers", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    private fun queryJson(rawUri: String): JSONObject {
        val cursor = context.contentResolver.query(Uri.parse(rawUri), null, null, null, null)
        cursor.use {
            requireNotNull(it)
            check(it.moveToFirst())
            return JSONObject(it.getString(it.getColumnIndexOrThrow("result")))
        }
    }

    private fun insert(rawUri: String, values: ContentValues): Uri {
        return requireNotNull(context.contentResolver.insert(Uri.parse(rawUri), values))
    }

    private fun encodeRuleJson(ruleId: String, name: String): String {
        val json = JSONObject().apply {
            put("id", ruleId)
            put("name", name)
            put("source", "USER_PRESENT")
            put("promptTemplate", "hello")
        }
        return Base64.encodeToString(json.toString().toByteArray(), Base64.NO_WRAP)
    }
}
