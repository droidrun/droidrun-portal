package com.mobilerun.portal.triggers

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

class TriggerRepository private constructor(context: Context) {

    companion object {
        private const val PREFS_NAME = "mobilerun_triggers"
        private const val KEY_SCHEMA_VERSION = "schema_version"
        private const val KEY_RULES_JSON = "rules_json"
        private const val KEY_RUNS_JSON = "runs_json"
        private const val MAX_RUN_RECORDS = 50

        @Volatile
        private var INSTANCE: TriggerRepository? = null

        fun getInstance(context: Context): TriggerRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TriggerRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val sharedPrefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        migrateIfNeeded()
    }

    @Synchronized
    fun listRules(): List<TriggerRule> {
        return TriggerJson.parseRules(sharedPrefs.getString(KEY_RULES_JSON, null))
            .sortedBy { it.name.lowercase() }
    }

    @Synchronized
    fun getRule(ruleId: String): TriggerRule? {
        return listRules().firstOrNull { it.id == ruleId }
    }

    @Synchronized
    fun saveRule(rule: TriggerRule) {
        val updatedRules = listRules()
            .filterNot { it.id == rule.id }
            .plus(rule)
            .sortedBy { it.name.lowercase() }
        persistRules(updatedRules)
    }

    @Synchronized
    fun deleteRule(ruleId: String) {
        persistRules(listRules().filterNot { it.id == ruleId })
    }

    @Synchronized
    fun updateRuleEnabled(ruleId: String, enabled: Boolean) {
        persistRules(listRules().map { rule ->
            if (rule.id == ruleId) rule.copy(enabled = enabled) else rule
        })
    }

    @Synchronized
    fun updateRuleTimestamps(ruleId: String, matchedAtMs: Long, launchedAtMs: Long?) {
        persistRules(listRules().map { rule ->
            if (rule.id != ruleId) {
                rule
            } else {
                rule.copy(
                    lastMatchedAtMs = matchedAtMs,
                    lastLaunchedAtMs = launchedAtMs ?: rule.lastLaunchedAtMs,
                )
            }
        })
    }

    @Synchronized
    fun listRuns(limit: Int = MAX_RUN_RECORDS): List<TriggerRunRecord> {
        return TriggerJson.parseRuns(sharedPrefs.getString(KEY_RUNS_JSON, null))
            .sortedByDescending { it.timestampMs }
            .take(limit)
    }

    @Synchronized
    fun addRun(record: TriggerRunRecord) {
        val updatedRuns = buildList {
            add(record)
            addAll(listRuns(MAX_RUN_RECORDS - 1))
        }
        persistRuns(updatedRuns)
    }

    @Synchronized
    fun deleteRun(runId: String) {
        persistRuns(
            TriggerJson.parseRuns(sharedPrefs.getString(KEY_RUNS_JSON, null))
                .filterNot { it.id == runId },
        )
    }

    @Synchronized
    fun clearRuns() {
        persistRuns(emptyList())
    }

    @Synchronized
    fun clearAll() {
        sharedPrefs.edit {
            putInt(KEY_SCHEMA_VERSION, TriggerJson.CURRENT_SCHEMA_VERSION)
            putString(KEY_RULES_JSON, "[]")
            putString(KEY_RUNS_JSON, "[]")
        }
    }

    private fun migrateIfNeeded() {
        val schemaVersion = sharedPrefs.getInt(KEY_SCHEMA_VERSION, 0)
        if (schemaVersion >= TriggerJson.CURRENT_SCHEMA_VERSION) return

        val migratedRules = TriggerJson.parseRules(sharedPrefs.getString(KEY_RULES_JSON, null))
        val migratedRuns = TriggerJson.parseRuns(sharedPrefs.getString(KEY_RUNS_JSON, null))
        sharedPrefs.edit(commit = true) {
            if (!sharedPrefs.contains(KEY_RULES_JSON)) {
                putString(KEY_RULES_JSON, "[]")
            } else {
                putString(KEY_RULES_JSON, TriggerJson.rulesToJsonArray(migratedRules).toString())
            }
            if (!sharedPrefs.contains(KEY_RUNS_JSON)) {
                putString(KEY_RUNS_JSON, "[]")
            } else {
                putString(KEY_RUNS_JSON, TriggerJson.runsToJsonArray(migratedRuns).toString())
            }
            putInt(KEY_SCHEMA_VERSION, TriggerJson.CURRENT_SCHEMA_VERSION)
        }
    }

    private fun persistRules(rules: List<TriggerRule>) {
        sharedPrefs.edit {
            putString(KEY_RULES_JSON, TriggerJson.rulesToJsonArray(rules).toString())
        }
    }

    private fun persistRuns(runs: List<TriggerRunRecord>) {
        sharedPrefs.edit {
            putString(KEY_RUNS_JSON, TriggerJson.runsToJsonArray(runs).toString())
        }
    }
}
