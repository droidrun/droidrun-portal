package com.mobilerun.portal.config

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.telephony.TelephonyManager
import androidx.core.content.edit
import com.mobilerun.portal.events.model.EventType
import com.mobilerun.portal.taskprompt.PortalActiveTaskRecord
import com.mobilerun.portal.taskprompt.PortalCloudClient
import com.mobilerun.portal.taskprompt.PortalTaskSettings
import com.mobilerun.portal.taskprompt.PortalTaskTracking
import com.mobilerun.portal.taskprompt.TaskPromptSettingsConstraints

/**
 * Centralized configuration manager for Mobilerun Portal
 * Handles SharedPreferences operations and provides a clean API for configuration management
 */
class ConfigManager private constructor(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "mobilerun_config"
        private const val DEVICE_PREFS_NAME = "mobilerun_device"
        private const val SECRET_PREFS_NAME = "mobilerun_secrets"
        private const val KEY_OVERLAY_VISIBLE = "overlay_visible"
        private const val KEY_OVERLAY_OFFSET = "overlay_offset"
        private const val KEY_AUTO_OFFSET_ENABLED = "auto_offset_enabled"
        private const val KEY_AUTO_OFFSET_CALCULATED = "auto_offset_calculated"
        private const val KEY_SOCKET_SERVER_ENABLED = "socket_server_enabled"
        private const val KEY_SOCKET_SERVER_PORT = "socket_server_port"

        // WebSocket & Events
        private const val KEY_WEBSOCKET_ENABLED = "websocket_enabled"
        private const val KEY_WEBSOCKET_PORT = "websocket_port"
        private const val KEY_REVERSE_CONNECTION_URL = "reverse_connection_url"
        private const val KEY_REVERSE_CONNECTION_TOKEN = "reverse_connection_token"
        private const val KEY_REVERSE_CONNECTION_ENABLED = "reverse_connection_enabled"
        private const val KEY_REVERSE_CONNECTION_SERVICE_KEY = "reverse_connection_service_key"
        private const val KEY_PRODUCTION_MODE = "production_mode"
        private const val KEY_DEV_MODE_ENABLED = "dev_mode_enabled"
        private const val KEY_INSTALL_AUTO_ACCEPT_ENABLED = "install_auto_accept_enabled"
        private const val KEY_KEEP_SCREEN_AWAKE_ENABLED = "keep_screen_awake_enabled"
        private const val KEY_KEEP_ALIVE_LAST_RECOVERY_AT_MS = "keep_alive_last_recovery_at_ms"
        private const val KEY_KEEP_ALIVE_LAST_RECOVERY_ATTEMPT_AT_MS =
            "keep_alive_last_recovery_attempt_at_ms"
        private const val KEY_KEEP_ALIVE_CONSECUTIVE_RECOVERY_FAILURES =
            "keep_alive_consecutive_recovery_failures"
        private const val KEY_KEEP_ALIVE_DEGRADED_REASON = "keep_alive_degraded_reason"
        private const val KEY_KEEP_ALIVE_NEXT_RECOVERY_TOKEN = "keep_alive_next_recovery_token"
        private const val KEY_KEEP_ALIVE_ACTIVE_RECOVERY_TOKEN = "keep_alive_active_recovery_token"
        private const val KEY_KEEP_ALIVE_RECOVERY_ACTIVITY_IN_FLIGHT =
            "keep_alive_recovery_activity_in_flight"
        private const val KEY_KEEP_ALIVE_PENDING_RECOVERY_RESULT_TOKEN =
            "keep_alive_pending_recovery_result_token"
        private const val KEY_KEEP_ALIVE_PENDING_RECOVERY_RESULT_SUCCESS =
            "keep_alive_pending_recovery_result_success"
        private const val KEY_KEEP_ALIVE_PENDING_RECOVERY_RESULT_REASON =
            "keep_alive_pending_recovery_result_reason"
        private const val KEY_KEEP_ALIVE_PENDING_RECOVERY_RESULT_AT_MS =
            "keep_alive_pending_recovery_result_at_ms"
        private const val KEY_KEEP_ALIVE_RECOVERY_OWNER_SESSION_ID =
            "keep_alive_recovery_owner_session_id"
        private const val KEY_TASK_PROMPT_MODEL = "task_prompt_model"
        private const val KEY_TASK_PROMPT_DEFAULT_MODEL = "task_prompt_default_model"
        private const val KEY_TASK_PROMPT_REASONING = "task_prompt_reasoning"
        private const val KEY_TASK_PROMPT_VISION = "task_prompt_vision"
        private const val KEY_TASK_PROMPT_MAX_STEPS = "task_prompt_max_steps"
        private const val KEY_TASK_PROMPT_TEMPERATURE = "task_prompt_temperature"
        private const val KEY_TASK_PROMPT_TIMEOUT = "task_prompt_timeout"
        private const val KEY_TASK_PROMPT_RETURN_TO_PORTAL = "task_prompt_return_to_portal"
        private const val KEY_ACTIVE_TASK_ID = "active_task_id"
        private const val KEY_ACTIVE_TASK_PROMPT_PREVIEW = "active_task_prompt_preview"
        private const val KEY_ACTIVE_TASK_STARTED_AT_MS = "active_task_started_at_ms"
        private const val KEY_ACTIVE_TASK_EXECUTION_TIMEOUT_SEC = "active_task_execution_timeout_sec"
        private const val KEY_ACTIVE_TASK_POLL_DEADLINE_MS = "active_task_poll_deadline_ms"
        private const val KEY_ACTIVE_TASK_LAST_STATUS = "active_task_last_status"
        private const val KEY_ACTIVE_TASK_STARTED_TOAST_SHOWN = "active_task_started_toast_shown"
        private const val KEY_ACTIVE_TASK_TERMINAL_TOAST_SHOWN = "active_task_terminal_toast_shown"
        private const val KEY_ACTIVE_TASK_TRIGGER_RULE_ID = "active_task_trigger_rule_id"
        private const val KEY_ACTIVE_TASK_RETURN_TO_PORTAL = "active_task_return_to_portal"
        private const val KEY_ACTIVE_TASK_TERMINAL_RETURN_HANDLED = "active_task_terminal_return_handled"
        private const val KEY_ACTIVE_TASK_TERMINAL_TRANSITION_HANDLED =
            "active_task_terminal_transition_handled"
        private const val PREFIX_EVENT_ENABLED = "event_enabled_"
        private const val KEY_AUTH_TOKEN = "auth_token"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_BROWSER_AUTH_PENDING_UNTIL_MS = "browser_auth_pending_until_ms"
        private const val DEVICE_ID_PLACEHOLDER = "{deviceId}"

        private const val DEFAULT_OFFSET = 0
        private const val DEFAULT_SOCKET_PORT = 8080
        private const val DEFAULT_WEBSOCKET_PORT = 8081
        private const val DEFAULT_REVERSE_CONNECTION_URL =
            "wss://api.mobilerun.ai/v1/providers/personal/join"

        // TODO replace
        @Volatile
        private var INSTANCE: ConfigManager? = null

        fun getInstance(context: Context): ConfigManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ConfigManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val sharedPrefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val devicePrefs: SharedPreferences =
        context.getSharedPreferences(DEVICE_PREFS_NAME, Context.MODE_PRIVATE)

    private val secretsPrefs: SharedPreferences =
        context.getSharedPreferences(SECRET_PREFS_NAME, Context.MODE_PRIVATE)

    init {
        migrateSecretPrefsIfNeeded()
        if (sharedPrefs.contains(KEY_REVERSE_CONNECTION_ENABLED)) {
            sharedPrefs.edit { putBoolean(KEY_REVERSE_CONNECTION_ENABLED, false) }
        }
        migrateTaskPromptModelPrefsIfNeeded()
    }

    // Auth Token (Auto-generated if missing)
    // TODO add external injection from some config file
    val authToken: String
        get() {
            var token = secretsPrefs.getString(KEY_AUTH_TOKEN, null)
            if (token == null) {
                token = java.util.UUID.randomUUID().toString()
                secretsPrefs.edit { putString(KEY_AUTH_TOKEN, token) }
            }
            return token
        }

    val deviceID: String
        get() {
            // Check new location first, then migrate from old location
            var id = devicePrefs.getString(KEY_DEVICE_ID, null)
            if (id == null) {
                id = sharedPrefs.getString(KEY_DEVICE_ID, null)
                if (id != null) {
                    // Migrate to new file and remove from old
                    devicePrefs.edit { putString(KEY_DEVICE_ID, id) }
                    sharedPrefs.edit { remove(KEY_DEVICE_ID) }
                } else {
                    id = java.util.UUID.randomUUID().toString()
                    devicePrefs.edit { putString(KEY_DEVICE_ID, id) }
                }
            }
            return id
        }

    // Overlay visibility
    var overlayVisible: Boolean
        get() = sharedPrefs.getBoolean(KEY_OVERLAY_VISIBLE, true)
        set(value) {
            sharedPrefs.edit { putBoolean(KEY_OVERLAY_VISIBLE, value) }
        }

    // Overlay offset
    var overlayOffset: Int
        get() = sharedPrefs.getInt(KEY_OVERLAY_OFFSET, DEFAULT_OFFSET)
        set(value) {
            sharedPrefs.edit { putInt(KEY_OVERLAY_OFFSET, value) }
        }

    // Auto offset enabled
    var autoOffsetEnabled: Boolean
        get() = sharedPrefs.getBoolean(KEY_AUTO_OFFSET_ENABLED, true)
        set(value) {
            sharedPrefs.edit { putBoolean(KEY_AUTO_OFFSET_ENABLED, value) }
        }

    // Track if auto offset has been calculated before
    var autoOffsetCalculated: Boolean
        get() = sharedPrefs.getBoolean(KEY_AUTO_OFFSET_CALCULATED, false)
        set(value) {
            sharedPrefs.edit { putBoolean(KEY_AUTO_OFFSET_CALCULATED, value) }
        }

    // Socket server enabled (REST API)
    var socketServerEnabled: Boolean
        get() = sharedPrefs.getBoolean(KEY_SOCKET_SERVER_ENABLED, false)
        set(value) {
            sharedPrefs.edit { putBoolean(KEY_SOCKET_SERVER_ENABLED, value) }
        }

    // Socket server port (REST API)
    var socketServerPort: Int
        get() = sharedPrefs.getInt(KEY_SOCKET_SERVER_PORT, DEFAULT_SOCKET_PORT)
        set(value) {
            sharedPrefs.edit { putInt(KEY_SOCKET_SERVER_PORT, value) }
        }

    // WebSocket Server Enabled
    var websocketEnabled: Boolean
        get() = sharedPrefs.getBoolean(KEY_WEBSOCKET_ENABLED, false) // Don't make it true
        set(value) {
            sharedPrefs.edit { putBoolean(KEY_WEBSOCKET_ENABLED, value) }
        }

    // WebSocket Server Port
    var websocketPort: Int
        get() = sharedPrefs.getInt(KEY_WEBSOCKET_PORT, DEFAULT_WEBSOCKET_PORT)
        set(value) {
            sharedPrefs.edit { putInt(KEY_WEBSOCKET_PORT, value) }
        }

    // Reverse Connection URL
    var reverseConnectionUrl: String
        get() {
            val stored = sharedPrefs.getString(KEY_REVERSE_CONNECTION_URL, null)
            return if (stored.isNullOrBlank()) DEFAULT_REVERSE_CONNECTION_URL else stored
        }
        set(value) {
            sharedPrefs.edit { putString(KEY_REVERSE_CONNECTION_URL, value) }
        }

    val reverseConnectionUrlOrDefault: String
        get() {
            val stored = reverseConnectionUrl
            return stored.ifBlank { DEFAULT_REVERSE_CONNECTION_URL }
        }

    val defaultReverseConnectionUrl: String
        get() = DEFAULT_REVERSE_CONNECTION_URL

    val reverseConnectionUrlForDisplay: String
        get() = reverseConnectionUrlOrDefault.replace(DEVICE_ID_PLACEHOLDER, deviceID)

    // Reverse Connection Token (Optional, for authenticating with Host/Cloud)
    var reverseConnectionToken: String
        get() = secretsPrefs.getString(KEY_REVERSE_CONNECTION_TOKEN, "") ?: ""
        set(value) {
            secretsPrefs.edit { putString(KEY_REVERSE_CONNECTION_TOKEN, value) }
        }

    // Reverse Connection Service Key (Header: X-Remote-Device-Key)
    var reverseConnectionServiceKey: String
        get() = secretsPrefs.getString(KEY_REVERSE_CONNECTION_SERVICE_KEY, "") ?: ""
        set(value) {
            secretsPrefs.edit { putString(KEY_REVERSE_CONNECTION_SERVICE_KEY, value) }
        }

    var productionMode: Boolean
        get() = sharedPrefs.getBoolean(KEY_PRODUCTION_MODE, false)
        set(value) {
            sharedPrefs.edit { putBoolean(KEY_PRODUCTION_MODE, value) }
            listeners.forEach { it.onProductionModeChanged(value) }
        }

    var devModeEnabled: Boolean
        get() = sharedPrefs.getBoolean(KEY_DEV_MODE_ENABLED, false)
        set(value) {
            sharedPrefs.edit { putBoolean(KEY_DEV_MODE_ENABLED, value) }
        }

    val deviceName: String
        get() {
            val manufacturer = Build.MANUFACTURER
            val model = Build.MODEL

            return if (model.startsWith(manufacturer)) {
                capitalize(model)
            } else {
                capitalize(manufacturer) + " " + model
            }
        }

    val deviceCountryCode: String
        get() {
            // Try to get country from SIM card first (most accurate for physical location)
            try {
                val telephonyManager =
                    context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                telephonyManager?.let { tm ->
                    // Try SIM country first
                    val simCountry = tm.simCountryIso
                    if (!simCountry.isNullOrBlank()) {
                        return simCountry.uppercase()
                    }

                    // Try network country
                    val networkCountry = tm.networkCountryIso
                    if (!networkCountry.isNullOrBlank()) {
                        return networkCountry.uppercase()
                    }
                }
            } catch (e: Exception) {
                // Ignore and fall back to locale
            }

            // Fall back to device locale country
            val locale =
                context.resources.configuration.locales[0]

            return locale.country.ifBlank { "US" }
        }

    var reverseConnectionEnabled: Boolean = false

    var forceLoginOnNextConnect: Boolean
        get() = sharedPrefs.getBoolean("force_login_on_next_connect", false)
        set(value) {
            sharedPrefs.edit { putBoolean("force_login_on_next_connect", value) }
        }

    var screenShareAutoAcceptEnabled: Boolean
        get() = sharedPrefs.getBoolean("screen_share_auto_accept_enabled", true)
        set(value) {
            sharedPrefs.edit { putBoolean("screen_share_auto_accept_enabled", value) }
        }

    fun markBrowserAuthPending(
        nowMs: Long = System.currentTimeMillis(),
        ttlMs: Long = 10 * 60 * 1000L,
    ) {
        sharedPrefs.edit {
            putLong(KEY_BROWSER_AUTH_PENDING_UNTIL_MS, nowMs + ttlMs.coerceAtLeast(0L))
        }
    }

    fun isBrowserAuthPending(nowMs: Long = System.currentTimeMillis()): Boolean {
        return sharedPrefs.getLong(KEY_BROWSER_AUTH_PENDING_UNTIL_MS, 0L) > nowMs
    }

    fun clearBrowserAuthPending() {
        sharedPrefs.edit { remove(KEY_BROWSER_AUTH_PENDING_UNTIL_MS) }
    }

    var installAutoAcceptEnabled: Boolean
        get() = sharedPrefs.getBoolean(KEY_INSTALL_AUTO_ACCEPT_ENABLED, false)
        set(value) {
            sharedPrefs.edit { putBoolean(KEY_INSTALL_AUTO_ACCEPT_ENABLED, value) }
        }

    var keepScreenAwakeEnabled: Boolean
        get() = sharedPrefs.getBoolean(KEY_KEEP_SCREEN_AWAKE_ENABLED, false)
        set(value) {
            sharedPrefs.edit { putBoolean(KEY_KEEP_SCREEN_AWAKE_ENABLED, value) }
        }

    var keepAliveLastRecoveryAtMs: Long
        get() = sharedPrefs.getLong(KEY_KEEP_ALIVE_LAST_RECOVERY_AT_MS, 0L)
        set(value) {
            sharedPrefs.edit { putLong(KEY_KEEP_ALIVE_LAST_RECOVERY_AT_MS, value) }
        }

    var keepAliveLastRecoveryAttemptAtMs: Long
        get() = sharedPrefs.getLong(KEY_KEEP_ALIVE_LAST_RECOVERY_ATTEMPT_AT_MS, 0L)
        set(value) {
            sharedPrefs.edit { putLong(KEY_KEEP_ALIVE_LAST_RECOVERY_ATTEMPT_AT_MS, value) }
        }

    var keepAliveConsecutiveRecoveryFailures: Int
        get() = sharedPrefs.getInt(KEY_KEEP_ALIVE_CONSECUTIVE_RECOVERY_FAILURES, 0)
        set(value) {
            sharedPrefs.edit { putInt(KEY_KEEP_ALIVE_CONSECUTIVE_RECOVERY_FAILURES, value) }
        }

    var keepAliveDegradedReason: String?
        get() = sharedPrefs.getString(KEY_KEEP_ALIVE_DEGRADED_REASON, null)?.takeIf { it.isNotBlank() }
        set(value) {
            sharedPrefs.edit {
                if (value.isNullOrBlank()) {
                    remove(KEY_KEEP_ALIVE_DEGRADED_REASON)
                } else {
                    putString(KEY_KEEP_ALIVE_DEGRADED_REASON, value)
                }
            }
        }

    @Synchronized
    fun nextKeepAliveRecoveryToken(): Long {
        val nextToken = sharedPrefs.getLong(KEY_KEEP_ALIVE_NEXT_RECOVERY_TOKEN, 0L) + 1L
        sharedPrefs.edit(commit = true) { putLong(KEY_KEEP_ALIVE_NEXT_RECOVERY_TOKEN, nextToken) }
        return nextToken
    }

    var keepAliveActiveRecoveryToken: Long
        get() = sharedPrefs.getLong(KEY_KEEP_ALIVE_ACTIVE_RECOVERY_TOKEN, 0L)
        set(value) {
            sharedPrefs.edit { putLong(KEY_KEEP_ALIVE_ACTIVE_RECOVERY_TOKEN, value) }
        }

    var keepAliveRecoveryActivityInFlight: Boolean
        get() = sharedPrefs.getBoolean(KEY_KEEP_ALIVE_RECOVERY_ACTIVITY_IN_FLIGHT, false)
        set(value) {
            sharedPrefs.edit { putBoolean(KEY_KEEP_ALIVE_RECOVERY_ACTIVITY_IN_FLIGHT, value) }
        }

    var keepAlivePendingRecoveryResultToken: Long
        get() = sharedPrefs.getLong(KEY_KEEP_ALIVE_PENDING_RECOVERY_RESULT_TOKEN, 0L)
        set(value) {
            sharedPrefs.edit { putLong(KEY_KEEP_ALIVE_PENDING_RECOVERY_RESULT_TOKEN, value) }
        }

    var keepAlivePendingRecoveryResultSuccess: Boolean
        get() = sharedPrefs.getBoolean(KEY_KEEP_ALIVE_PENDING_RECOVERY_RESULT_SUCCESS, false)
        set(value) {
            sharedPrefs.edit { putBoolean(KEY_KEEP_ALIVE_PENDING_RECOVERY_RESULT_SUCCESS, value) }
        }

    var keepAlivePendingRecoveryResultReason: String?
        get() = sharedPrefs.getString(KEY_KEEP_ALIVE_PENDING_RECOVERY_RESULT_REASON, null)?.takeIf { it.isNotBlank() }
        set(value) {
            sharedPrefs.edit {
                if (value.isNullOrBlank()) {
                    remove(KEY_KEEP_ALIVE_PENDING_RECOVERY_RESULT_REASON)
                } else {
                    putString(KEY_KEEP_ALIVE_PENDING_RECOVERY_RESULT_REASON, value)
                }
            }
        }

    var keepAlivePendingRecoveryResultAtMs: Long
        get() = sharedPrefs.getLong(KEY_KEEP_ALIVE_PENDING_RECOVERY_RESULT_AT_MS, 0L)
        set(value) {
            sharedPrefs.edit { putLong(KEY_KEEP_ALIVE_PENDING_RECOVERY_RESULT_AT_MS, value) }
        }

    var keepAliveRecoveryOwnerSessionId: String?
        get() = sharedPrefs.getString(KEY_KEEP_ALIVE_RECOVERY_OWNER_SESSION_ID, null)?.takeIf { it.isNotBlank() }
        set(value) {
            sharedPrefs.edit {
                if (value.isNullOrBlank()) {
                    remove(KEY_KEEP_ALIVE_RECOVERY_OWNER_SESSION_ID)
                } else {
                    putString(KEY_KEEP_ALIVE_RECOVERY_OWNER_SESSION_ID, value)
                }
            }
        }

    fun saveKeepAlivePendingRecoveryResult(
        token: Long,
        success: Boolean,
        reason: String?,
        completedAtMs: Long,
    ) {
        keepAlivePendingRecoveryResultToken = token
        keepAlivePendingRecoveryResultSuccess = success
        keepAlivePendingRecoveryResultReason = reason
        keepAlivePendingRecoveryResultAtMs = completedAtMs
    }

    fun clearKeepAlivePendingRecoveryResult() {
        keepAlivePendingRecoveryResultToken = 0L
        keepAlivePendingRecoveryResultSuccess = false
        keepAlivePendingRecoveryResultReason = null
        keepAlivePendingRecoveryResultAtMs = 0L
    }

    fun clearKeepAliveRecoveryHandoffState() {
        keepAliveActiveRecoveryToken = 0L
        keepAliveRecoveryOwnerSessionId = null
        keepAliveRecoveryActivityInFlight = false
        clearKeepAlivePendingRecoveryResult()
    }

    var taskPromptModel: String
        get() = sharedPrefs.getString(KEY_TASK_PROMPT_MODEL, "") ?: ""
        set(value) {
            sharedPrefs.edit {
                if (value.isBlank()) {
                    remove(KEY_TASK_PROMPT_MODEL)
                } else {
                    putString(KEY_TASK_PROMPT_MODEL, value)
                }
            }
        }

    var taskPromptDefaultModel: String
        get() = sharedPrefs.getString(
            KEY_TASK_PROMPT_DEFAULT_MODEL,
            PortalCloudClient.DEFAULT_MODEL_ID,
        )?.takeIf { it.isNotBlank() } ?: PortalCloudClient.DEFAULT_MODEL_ID
        set(value) {
            sharedPrefs.edit {
                putString(
                    KEY_TASK_PROMPT_DEFAULT_MODEL,
                    value.ifBlank { PortalCloudClient.DEFAULT_MODEL_ID },
                )
            }
        }

    val effectiveTaskPromptModel: String
        get() = taskPromptModel.takeIf { it.isNotBlank() }
            ?: taskPromptDefaultModel.takeIf { it.isNotBlank() }
            ?: PortalCloudClient.DEFAULT_MODEL_ID

    private fun migrateSecretPrefsIfNeeded() {
        migrateSecretKey(KEY_AUTH_TOKEN)
        migrateSecretKey(KEY_REVERSE_CONNECTION_TOKEN)
        migrateSecretKey(KEY_REVERSE_CONNECTION_SERVICE_KEY)
    }

    private fun migrateSecretKey(key: String) {
        if (secretsPrefs.contains(key)) {
            return
        }
        val legacyValue = sharedPrefs.getString(key, null) ?: return
        secretsPrefs.edit { putString(key, legacyValue) }
        sharedPrefs.edit { remove(key) }
    }

    var taskPromptReasoning: Boolean
        get() = sharedPrefs.getBoolean(
            KEY_TASK_PROMPT_REASONING,
            PortalCloudClient.DEFAULT_REASONING,
        )
        set(value) {
            sharedPrefs.edit { putBoolean(KEY_TASK_PROMPT_REASONING, value) }
        }

    var taskPromptVision: Boolean
        get() = sharedPrefs.getBoolean(KEY_TASK_PROMPT_VISION, PortalCloudClient.DEFAULT_VISION)
        set(value) {
            sharedPrefs.edit { putBoolean(KEY_TASK_PROMPT_VISION, value) }
        }

    var taskPromptMaxSteps: Int
        get() = TaskPromptSettingsConstraints.clampMaxSteps(
            sharedPrefs.getInt(
                KEY_TASK_PROMPT_MAX_STEPS,
                PortalCloudClient.DEFAULT_MAX_STEPS,
            ),
        )
        set(value) {
            sharedPrefs.edit {
                putInt(
                    KEY_TASK_PROMPT_MAX_STEPS,
                    TaskPromptSettingsConstraints.clampMaxSteps(value),
                )
            }
        }

    var taskPromptTemperature: Float
        get() = TaskPromptSettingsConstraints.clampTemperature(
            sharedPrefs.getFloat(
                KEY_TASK_PROMPT_TEMPERATURE,
                PortalCloudClient.DEFAULT_TEMPERATURE.toFloat(),
            ),
        )
        set(value) {
            sharedPrefs.edit {
                putFloat(
                    KEY_TASK_PROMPT_TEMPERATURE,
                    TaskPromptSettingsConstraints.clampTemperature(value),
                )
            }
        }

    var taskPromptExecutionTimeout: Int
        get() = TaskPromptSettingsConstraints.clampExecutionTimeout(
            sharedPrefs.getInt(
                KEY_TASK_PROMPT_TIMEOUT,
                PortalCloudClient.DEFAULT_EXECUTION_TIMEOUT,
            ),
        )
        set(value) {
            sharedPrefs.edit {
                putInt(
                    KEY_TASK_PROMPT_TIMEOUT,
                    TaskPromptSettingsConstraints.clampExecutionTimeout(value),
                )
            }
        }

    var taskPromptReturnToPortal: Boolean
        get() = sharedPrefs.getBoolean(KEY_TASK_PROMPT_RETURN_TO_PORTAL, false)
        set(value) {
            sharedPrefs.edit { putBoolean(KEY_TASK_PROMPT_RETURN_TO_PORTAL, value) }
        }

    val taskPromptSettings: PortalTaskSettings
        get() = PortalTaskSettings(
            llmModel = effectiveTaskPromptModel,
            reasoning = taskPromptReasoning,
            vision = taskPromptVision,
            maxSteps = taskPromptMaxSteps,
            temperature = taskPromptTemperature.toDouble(),
            executionTimeout = taskPromptExecutionTimeout,
        )

    fun saveTaskPromptSettings(settings: PortalTaskSettings) {
        taskPromptModel = settings.llmModel
        taskPromptReasoning = settings.reasoning
        taskPromptVision = settings.vision
        taskPromptMaxSteps = settings.maxSteps
        taskPromptTemperature = settings.temperature.toFloat()
        taskPromptExecutionTimeout = settings.executionTimeout
    }

    fun updateTaskPromptDefaultModel(modelId: String) {
        if (modelId.isBlank()) return
        taskPromptDefaultModel = modelId
    }

    val activePortalTask: PortalActiveTaskRecord?
        get() {
            val taskId = sharedPrefs.getString(KEY_ACTIVE_TASK_ID, null)?.trim().orEmpty()
            if (taskId.isBlank()) return null

            return PortalActiveTaskRecord(
                taskId = taskId,
                promptPreview = sharedPrefs.getString(KEY_ACTIVE_TASK_PROMPT_PREVIEW, "") ?: "",
                startedAtMs = sharedPrefs.getLong(KEY_ACTIVE_TASK_STARTED_AT_MS, 0L),
                executionTimeoutSec = sharedPrefs.getInt(
                    KEY_ACTIVE_TASK_EXECUTION_TIMEOUT_SEC,
                    PortalCloudClient.DEFAULT_EXECUTION_TIMEOUT,
                ),
                pollDeadlineMs = sharedPrefs.getLong(KEY_ACTIVE_TASK_POLL_DEADLINE_MS, 0L),
                lastStatus = sharedPrefs.getString(
                    KEY_ACTIVE_TASK_LAST_STATUS,
                    PortalTaskTracking.STATUS_CREATED,
                ) ?: PortalTaskTracking.STATUS_CREATED,
                startedToastShown = sharedPrefs.getBoolean(
                    KEY_ACTIVE_TASK_STARTED_TOAST_SHOWN,
                    false,
                ),
                terminalToastShown = sharedPrefs.getBoolean(
                    KEY_ACTIVE_TASK_TERMINAL_TOAST_SHOWN,
                    false,
                ),
                triggerRuleId = sharedPrefs.getString(KEY_ACTIVE_TASK_TRIGGER_RULE_ID, null),
                returnToPortalOnTerminal = sharedPrefs.getBoolean(
                    KEY_ACTIVE_TASK_RETURN_TO_PORTAL,
                    false,
                ),
                terminalReturnHandled = sharedPrefs.getBoolean(
                    KEY_ACTIVE_TASK_TERMINAL_RETURN_HANDLED,
                    false,
                ),
                terminalTransitionHandled = sharedPrefs.getBoolean(
                    KEY_ACTIVE_TASK_TERMINAL_TRANSITION_HANDLED,
                    false,
                ),
            )
        }

    fun saveActivePortalTask(record: PortalActiveTaskRecord) {
        sharedPrefs.edit {
            putString(KEY_ACTIVE_TASK_ID, record.taskId)
            putString(KEY_ACTIVE_TASK_PROMPT_PREVIEW, record.promptPreview)
            putLong(KEY_ACTIVE_TASK_STARTED_AT_MS, record.startedAtMs)
            putInt(KEY_ACTIVE_TASK_EXECUTION_TIMEOUT_SEC, record.executionTimeoutSec)
            putLong(KEY_ACTIVE_TASK_POLL_DEADLINE_MS, record.pollDeadlineMs)
            putString(KEY_ACTIVE_TASK_LAST_STATUS, record.lastStatus)
            putBoolean(KEY_ACTIVE_TASK_STARTED_TOAST_SHOWN, record.startedToastShown)
            putBoolean(KEY_ACTIVE_TASK_TERMINAL_TOAST_SHOWN, record.terminalToastShown)
            putString(KEY_ACTIVE_TASK_TRIGGER_RULE_ID, record.triggerRuleId)
            putBoolean(KEY_ACTIVE_TASK_RETURN_TO_PORTAL, record.returnToPortalOnTerminal)
            putBoolean(KEY_ACTIVE_TASK_TERMINAL_RETURN_HANDLED, record.terminalReturnHandled)
            putBoolean(
                KEY_ACTIVE_TASK_TERMINAL_TRANSITION_HANDLED,
                record.terminalTransitionHandled,
            )
        }
    }

    fun clearActivePortalTask() {
        sharedPrefs.edit {
            remove(KEY_ACTIVE_TASK_ID)
            remove(KEY_ACTIVE_TASK_PROMPT_PREVIEW)
            remove(KEY_ACTIVE_TASK_STARTED_AT_MS)
            remove(KEY_ACTIVE_TASK_EXECUTION_TIMEOUT_SEC)
            remove(KEY_ACTIVE_TASK_POLL_DEADLINE_MS)
            remove(KEY_ACTIVE_TASK_LAST_STATUS)
            remove(KEY_ACTIVE_TASK_STARTED_TOAST_SHOWN)
            remove(KEY_ACTIVE_TASK_TERMINAL_TOAST_SHOWN)
            remove(KEY_ACTIVE_TASK_TRIGGER_RULE_ID)
            remove(KEY_ACTIVE_TASK_RETURN_TO_PORTAL)
            remove(KEY_ACTIVE_TASK_TERMINAL_RETURN_HANDLED)
            remove(KEY_ACTIVE_TASK_TERMINAL_TRANSITION_HANDLED)
        }
    }

    private fun migrateTaskPromptModelPrefsIfNeeded() {
        if (sharedPrefs.contains(KEY_TASK_PROMPT_DEFAULT_MODEL)) return

        val legacyExplicitModel = sharedPrefs.getString(KEY_TASK_PROMPT_MODEL, "")?.trim().orEmpty()
        sharedPrefs.edit {
            putString(KEY_TASK_PROMPT_DEFAULT_MODEL, PortalCloudClient.DEFAULT_MODEL_ID)
            if (legacyExplicitModel == PortalCloudClient.DEFAULT_MODEL_ID) {
                remove(KEY_TASK_PROMPT_MODEL)
            }
        }
    }

    // Listener interface for configuration changes
    interface ConfigChangeListener {
        fun onOverlayVisibilityChanged(visible: Boolean)
        fun onOverlayOffsetChanged(offset: Int)
        fun onSocketServerEnabledChanged(enabled: Boolean)
        fun onSocketServerPortChanged(port: Int)

        // New WebSocket listeners
        fun onWebSocketEnabledChanged(enabled: Boolean) {}
        fun onWebSocketPortChanged(port: Int) {}
        fun onKeepScreenAwakeEnabledChanged(enabled: Boolean) {}

        fun onProductionModeChanged(enabled: Boolean) {}
    }

    private val listeners = mutableSetOf<ConfigChangeListener>()

    fun capitalize(str: String): String {
        return str.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }

    fun normalizeReverseConnectionUrlForStorage(input: String): String {
        if (input.isBlank()) return ""
        return input.replace(deviceID, DEVICE_ID_PLACEHOLDER)
    }

    // Dynamic Event Toggles
    fun isEventEnabled(type: EventType): Boolean {
        // Default all events to true unless explicitly disabled
        return sharedPrefs.getBoolean(PREFIX_EVENT_ENABLED + type.name, true)
    }

    fun setEventEnabled(type: EventType, enabled: Boolean) {
        sharedPrefs.edit { putBoolean(PREFIX_EVENT_ENABLED + type.name, enabled) }
        // We could notify listeners here if needed, but usually this is polled by EventHub
    }

    fun addListener(listener: ConfigChangeListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: ConfigChangeListener) {
        listeners.remove(listener)
    }

    fun setOverlayVisibleWithNotification(visible: Boolean) {
        overlayVisible = visible
        listeners.forEach { it.onOverlayVisibilityChanged(visible) }
    }

    fun setOverlayOffsetWithNotification(offset: Int) {
        overlayOffset = offset
        listeners.forEach { it.onOverlayOffsetChanged(offset) }
    }

    fun setSocketServerEnabledWithNotification(enabled: Boolean) {
        socketServerEnabled = enabled
        listeners.forEach { it.onSocketServerEnabledChanged(enabled) }
    }

    fun setSocketServerPortWithNotification(port: Int) {
        socketServerPort = port
        listeners.forEach { it.onSocketServerPortChanged(port) }
    }

    fun setWebSocketEnabledWithNotification(enabled: Boolean) {
        websocketEnabled = enabled
        listeners.forEach { it.onWebSocketEnabledChanged(enabled) }
    }

    fun setWebSocketPortWithNotification(port: Int) {
        websocketPort = port
        listeners.forEach { it.onWebSocketPortChanged(port) }
    }

    fun setKeepScreenAwakeEnabledWithNotification(enabled: Boolean) {
        if (keepScreenAwakeEnabled == enabled) return
        keepScreenAwakeEnabled = enabled
        listeners.forEach { it.onKeepScreenAwakeEnabledChanged(enabled) }
    }

    fun clearKeepAliveRuntimeState() {
        keepAliveLastRecoveryAtMs = 0L
        keepAliveLastRecoveryAttemptAtMs = 0L
        keepAliveConsecutiveRecoveryFailures = 0
        keepAliveDegradedReason = null
        clearKeepAliveRecoveryHandoffState()
    }

    // Bulk configuration update
    fun updateConfiguration(
        overlayVisible: Boolean? = null,
        overlayOffset: Int? = null,
        autoOffsetEnabled: Boolean? = null,
        socketServerEnabled: Boolean? = null,
        socketServerPort: Int? = null,
        websocketEnabled: Boolean? = null,
        websocketPort: Int? = null
    ) {
        val editor = sharedPrefs.edit()
        var hasChanges = false

        overlayVisible?.let {
            editor.putBoolean(KEY_OVERLAY_VISIBLE, it)
            hasChanges = true
        }

        overlayOffset?.let {
            editor.putInt(KEY_OVERLAY_OFFSET, it)
            hasChanges = true
        }

        autoOffsetEnabled?.let {
            editor.putBoolean(KEY_AUTO_OFFSET_ENABLED, it)
            hasChanges = true
        }

        socketServerEnabled?.let {
            editor.putBoolean(KEY_SOCKET_SERVER_ENABLED, it)
            hasChanges = true
        }

        socketServerPort?.let {
            editor.putInt(KEY_SOCKET_SERVER_PORT, it)
            hasChanges = true
        }

        websocketEnabled?.let {
            editor.putBoolean(KEY_WEBSOCKET_ENABLED, it)
            hasChanges = true
        }

        websocketPort?.let {
            editor.putInt(KEY_WEBSOCKET_PORT, it)
            hasChanges = true
        }

        if (hasChanges) {
            editor.apply()

            // Notify listeners
            overlayVisible?.let {
                listeners.forEach { listener ->
                    listener.onOverlayVisibilityChanged(
                        it
                    )
                }
            }
            overlayOffset?.let { listeners.forEach { listener -> listener.onOverlayOffsetChanged(it) } }
            socketServerEnabled?.let {
                listeners.forEach { listener ->
                    listener.onSocketServerEnabledChanged(
                        it
                    )
                }
            }
            socketServerPort?.let {
                listeners.forEach { listener ->
                    listener.onSocketServerPortChanged(
                        it
                    )
                }
            }
            websocketEnabled?.let {
                listeners.forEach { listener ->
                    listener.onWebSocketEnabledChanged(
                        it
                    )
                }
            }
            websocketPort?.let { listeners.forEach { listener -> listener.onWebSocketPortChanged(it) } }
        }
    }

    fun resetToDefaults() {
        sharedPrefs.edit(commit = true) {
            clear()
            putBoolean(KEY_OVERLAY_VISIBLE, true)
            putInt(KEY_OVERLAY_OFFSET, DEFAULT_OFFSET)
            putBoolean(KEY_AUTO_OFFSET_ENABLED, true)
            putBoolean(KEY_AUTO_OFFSET_CALCULATED, false)
            putBoolean(KEY_SOCKET_SERVER_ENABLED, false)
            putInt(KEY_SOCKET_SERVER_PORT, DEFAULT_SOCKET_PORT)
            putBoolean(KEY_WEBSOCKET_ENABLED, false)
            putInt(KEY_WEBSOCKET_PORT, DEFAULT_WEBSOCKET_PORT)
            putString(KEY_REVERSE_CONNECTION_URL, DEFAULT_REVERSE_CONNECTION_URL)
            putString(KEY_REVERSE_CONNECTION_TOKEN, "")
            putBoolean(KEY_REVERSE_CONNECTION_ENABLED, false)
            putString(KEY_REVERSE_CONNECTION_SERVICE_KEY, "")
            putBoolean(KEY_PRODUCTION_MODE, false)
            putBoolean(KEY_DEV_MODE_ENABLED, false)
            putBoolean(KEY_INSTALL_AUTO_ACCEPT_ENABLED, false)
            putBoolean(KEY_KEEP_SCREEN_AWAKE_ENABLED, false)
            putBoolean("screen_share_auto_accept_enabled", true)
            putBoolean("force_login_on_next_connect", false)
        }
        // Notify listeners
        listeners.forEach {
            it.onOverlayVisibilityChanged(true)
            it.onOverlayOffsetChanged(DEFAULT_OFFSET)
            it.onSocketServerEnabledChanged(false)
            it.onSocketServerPortChanged(DEFAULT_SOCKET_PORT)
            it.onWebSocketEnabledChanged(false)
            it.onWebSocketPortChanged(DEFAULT_WEBSOCKET_PORT)
            it.onKeepScreenAwakeEnabledChanged(false)
            it.onProductionModeChanged(false)
        }
    }

    // Get all configuration as a data class
    data class Configuration(
        val overlayVisible: Boolean,
        val overlayOffset: Int,
        val autoOffsetEnabled: Boolean,
        val autoOffsetCalculated: Boolean,
        val socketServerEnabled: Boolean,
        val socketServerPort: Int,
        val websocketEnabled: Boolean,
        val websocketPort: Int,
        val keepScreenAwakeEnabled: Boolean,
        val authToken: String
    )

    fun getCurrentConfiguration(): Configuration {
        return Configuration(
            overlayVisible = overlayVisible,
            overlayOffset = overlayOffset,
            autoOffsetEnabled = autoOffsetEnabled,
            autoOffsetCalculated = autoOffsetCalculated,
            socketServerEnabled = socketServerEnabled,
            socketServerPort = socketServerPort,
            websocketEnabled = websocketEnabled,
            websocketPort = websocketPort,
            keepScreenAwakeEnabled = keepScreenAwakeEnabled,
            authToken = authToken
        )
    }
}
