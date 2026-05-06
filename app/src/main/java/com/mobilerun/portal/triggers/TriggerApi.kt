package com.mobilerun.portal.triggers

import android.Manifest
import android.app.AlarmManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.mobilerun.portal.events.model.EventType
import com.mobilerun.portal.service.MobilerunAccessibilityService
import com.mobilerun.portal.service.MobilerunNotificationListener
import org.json.JSONArray
import org.json.JSONObject

data class TriggerEnvironmentStatus(
    val accessibilityServiceConnected: Boolean,
    val notificationAccessEnabled: Boolean,
    val receiveSmsGranted: Boolean,
    val readContactsGranted: Boolean,
    val exactAlarmAvailable: Boolean,
)

fun interface TriggerEnvironmentStatusProvider {
    fun get(context: Context): TriggerEnvironmentStatus
}

interface TriggerOperations {
    fun listRules(): List<TriggerRule>

    fun getRule(ruleId: String): TriggerRule?

    fun saveRule(rule: TriggerRule)

    fun deleteRule(ruleId: String)

    fun setRuleEnabled(ruleId: String, enabled: Boolean)

    fun launchTest(ruleId: String)

    fun listRuns(limit: Int = 50): List<TriggerRunRecord>

    fun deleteRun(runId: String)

    fun clearRuns()
}

private class TriggerRuntimeOperations(
    private val appContext: Context,
) : TriggerOperations {
    private fun ensureInitialized() {
        TriggerRuntime.initialize(appContext)
    }

    override fun listRules(): List<TriggerRule> {
        ensureInitialized()
        return TriggerRuntime.listRules()
    }

    override fun getRule(ruleId: String): TriggerRule? = listRules().firstOrNull { it.id == ruleId }

    override fun saveRule(rule: TriggerRule) {
        ensureInitialized()
        TriggerRuntime.saveRule(rule)
    }

    override fun deleteRule(ruleId: String) {
        ensureInitialized()
        TriggerRuntime.deleteRule(ruleId)
    }

    override fun setRuleEnabled(ruleId: String, enabled: Boolean) {
        ensureInitialized()
        TriggerRuntime.setRuleEnabled(ruleId, enabled)
    }

    override fun launchTest(ruleId: String) {
        ensureInitialized()
        TriggerRuntime.launchTest(ruleId)
    }

    override fun listRuns(limit: Int): List<TriggerRunRecord> {
        ensureInitialized()
        return TriggerRuntime.listRuns(limit)
    }

    override fun deleteRun(runId: String) {
        ensureInitialized()
        TriggerRuntime.deleteRun(runId)
    }

    override fun clearRuns() {
        ensureInitialized()
        TriggerRuntime.clearRuns()
    }
}

sealed class TriggerApiResult<out T> {
    data class Success<T>(
        val value: T,
        val message: String? = null,
    ) : TriggerApiResult<T>()

    data class Error(
        val message: String,
    ) : TriggerApiResult<Nothing>()
}

class TriggerApi(
    context: Context,
    private val operations: TriggerOperations = TriggerRuntimeOperations(context.applicationContext),
    private val environmentStatusProvider: TriggerEnvironmentStatusProvider =
        TriggerEnvironmentStatusProvider(::resolveEnvironmentStatus),
    private val nowProvider: () -> Long = { System.currentTimeMillis() },
) {
    private val appContext = context.applicationContext

    fun catalog(): JSONObject {
        return JSONObject().apply {
            put("schemaVersion", TriggerJson.CURRENT_SCHEMA_VERSION)
            put("eventTypes", JSONArray(EventType.entries.map { it.name }))
            put("triggerSources", JSONArray(TriggerSource.entries.map { it.name }))
            put("runDispositions", JSONArray(TriggerRunDisposition.entries.map { it.name }))
            put("stringMatchModes", JSONArray(TriggerStringMatchMode.entries.map { it.name }))
            put("networkTypes", JSONArray(TriggerNetworkType.entries.map { it.name }))
            put(
                "thresholdComparisons",
                JSONArray(TriggerThresholdComparison.entries.map { it.name }),
            )
            put("sourceMetadata", buildSourceMetadata())
        }
    }

    fun status(): JSONObject {
        val environment = environmentStatusProvider.get(appContext)
        return JSONObject().apply {
            put("accessibilityServiceConnected", environment.accessibilityServiceConnected)
            put("notificationAccessEnabled", environment.notificationAccessEnabled)
            put("receiveSmsGranted", environment.receiveSmsGranted)
            put("readContactsGranted", environment.readContactsGranted)
            put("exactAlarmAvailable", environment.exactAlarmAvailable)
            put("ruleCount", operations.listRules().size)
            put("runCount", operations.listRuns(Int.MAX_VALUE).size)
            put("schemaVersion", TriggerJson.CURRENT_SCHEMA_VERSION)
        }
    }

    fun listRules(): JSONArray = TriggerJson.rulesToJsonArray(operations.listRules())

    fun getRule(ruleId: String): TriggerApiResult<JSONObject> {
        if (ruleId.isBlank()) return TriggerApiResult.Error("Missing rule id")
        val rule = operations.getRule(ruleId) ?: return TriggerApiResult.Error(
            "Trigger rule not found: $ruleId",
        )
        return TriggerApiResult.Success(TriggerJson.ruleToJson(rule))
    }

    fun listRuns(limit: Int = 50): JSONArray {
        val resolvedLimit = limit.coerceAtLeast(1)
        return TriggerJson.runsToJsonArray(operations.listRuns(resolvedLimit))
    }

    fun saveRule(rawRuleJson: String): TriggerApiResult<JSONObject> {
        val parsed = try {
            JSONObject(rawRuleJson)
        } catch (e: Exception) {
            return TriggerApiResult.Error("Invalid rule_json: ${e.message}")
        }
        val rule = try {
            TriggerJson.ruleFromJson(parsed)
        } catch (e: Exception) {
            return TriggerApiResult.Error("Invalid trigger rule: ${e.message}")
        }
        val validation = TriggerRuleValidator.validateForSave(rule, nowProvider())
        val validRule = validation.rule ?: return TriggerApiResult.Error(
            validation.firstMessage() ?: "Invalid trigger rule",
        )

        operations.saveRule(validRule)
        val savedRule = operations.getRule(validRule.id) ?: validRule
        return TriggerApiResult.Success(
            TriggerJson.ruleToJson(savedRule),
            "Saved trigger rule ${savedRule.id}",
        )
    }

    fun deleteRule(ruleId: String): TriggerApiResult<String> {
        if (ruleId.isBlank()) return TriggerApiResult.Error("Missing rule id")
        val rule = operations.getRule(ruleId) ?: return TriggerApiResult.Error(
            "Trigger rule not found: $ruleId",
        )
        operations.deleteRule(ruleId)
        return TriggerApiResult.Success("Deleted trigger rule ${rule.id}")
    }

    fun setRuleEnabled(ruleId: String, enabled: Boolean): TriggerApiResult<JSONObject> {
        if (ruleId.isBlank()) return TriggerApiResult.Error("Missing rule id")
        val existingRule = operations.getRule(ruleId) ?: return TriggerApiResult.Error(
            "Trigger rule not found: $ruleId",
        )
        operations.setRuleEnabled(ruleId, enabled)
        val updatedRule = operations.getRule(ruleId) ?: existingRule.copy(enabled = enabled)
        return TriggerApiResult.Success(
            TriggerJson.ruleToJson(updatedRule),
            "Updated trigger rule ${updatedRule.id}",
        )
    }

    fun testRule(ruleId: String): TriggerApiResult<String> {
        if (ruleId.isBlank()) return TriggerApiResult.Error("Missing rule id")
        val rule = operations.getRule(ruleId) ?: return TriggerApiResult.Error(
            "Trigger rule not found: $ruleId",
        )
        operations.launchTest(ruleId)
        return TriggerApiResult.Success("Test run requested for ${rule.id}")
    }

    fun deleteRun(runId: String): TriggerApiResult<String> {
        if (runId.isBlank()) return TriggerApiResult.Error("Missing run id")
        val runExists = operations.listRuns(Int.MAX_VALUE).any { it.id == runId }
        if (!runExists) return TriggerApiResult.Error("Trigger run not found: $runId")
        operations.deleteRun(runId)
        return TriggerApiResult.Success("Deleted trigger run $runId")
    }

    fun clearRuns(): TriggerApiResult<String> {
        operations.clearRuns()
        return TriggerApiResult.Success("Cleared trigger runs")
    }

    private fun buildSourceMetadata(): JSONObject {
        return JSONObject().apply {
            TriggerSource.entries.forEach { source ->
                put(
                    source.name,
                    JSONObject().apply {
                        put("label", TriggerUiSupport.sourceLabel(source))
                        put("description", TriggerUiSupport.sourceDescription(source))
                        put(
                            "defaultCooldownSeconds",
                            TriggerEditorSupport.defaultCooldownSecondsFor(source),
                        )
                        put("capabilities", capabilitiesToJson(TriggerEditorSupport.capabilitiesFor(source)))
                        put("visibility", visibilityToJson(TriggerEditorSupport.visibilityFor(source)))
                    },
                )
            }
        }
    }

    private fun capabilitiesToJson(
        capabilities: TriggerEditorSupport.Capabilities,
    ): JSONObject {
        return JSONObject().apply {
            put("supportsCooldown", capabilities.supportsCooldown)
            put("supportsRunLimit", capabilities.supportsRunLimit)
        }
    }

    private fun visibilityToJson(
        visibility: TriggerEditorSupport.Visibility,
    ): JSONObject {
        return JSONObject().apply {
            put("showMatchMode", visibility.showMatchMode)
            put("showPackageName", visibility.showPackageName)
            put("showTitleFilter", visibility.showTitleFilter)
            put("showTextFilter", visibility.showTextFilter)
            put("showThreshold", visibility.showThreshold)
            put("showNetworkType", visibility.showNetworkType)
            put("showPhoneNumber", visibility.showPhoneNumber)
            put("showMessageFilter", visibility.showMessageFilter)
            put("showDelay", visibility.showDelay)
            put("showAbsoluteTime", visibility.showAbsoluteTime)
            put("showRecurringTime", visibility.showRecurringTime)
            put("showCooldown", visibility.showCooldown)
            put("showRunLimit", visibility.showRunLimit)
        }
    }

    companion object {
        private fun resolveEnvironmentStatus(context: Context): TriggerEnvironmentStatus {
            return TriggerEnvironmentStatus(
                accessibilityServiceConnected = MobilerunAccessibilityService.getInstance() != null,
                notificationAccessEnabled = isNotificationAccessEnabled(context),
                receiveSmsGranted = hasPermission(context, Manifest.permission.RECEIVE_SMS),
                readContactsGranted = hasPermission(context, Manifest.permission.READ_CONTACTS),
                exactAlarmAvailable = hasExactAlarmAccess(context),
            )
        }

        private fun isNotificationAccessEnabled(context: Context): Boolean {
            val componentName = ComponentName(context, MobilerunNotificationListener::class.java)
            val enabledListeners = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners",
            )
            return enabledListeners?.contains(componentName.flattenToString()) == true
        }

        private fun hasPermission(context: Context, permission: String): Boolean {
            return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }

        private fun hasExactAlarmAccess(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
            return context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms()
                ?: false
        }
    }
}
