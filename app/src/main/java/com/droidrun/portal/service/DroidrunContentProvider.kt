package com.droidrun.portal.service

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import com.droidrun.portal.api.ApiHandler
import com.droidrun.portal.api.ApiResponse
import com.droidrun.portal.config.ConfigManager
import com.droidrun.portal.core.StateRepository
import com.droidrun.portal.input.DroidrunKeyboardIME
import com.droidrun.portal.triggers.TriggerApi
import com.droidrun.portal.triggers.TriggerApiResult

import android.util.Base64

class DroidrunContentProvider : ContentProvider() {
    companion object {
        private const val TAG = "DroidrunContentProvider"
        private const val AUTHORITY = "com.droidrun.portal"
        private const val A11Y_TREE = 1
        private const val PHONE_STATE = 2
        private const val PING = 3
        private const val KEYBOARD_ACTIONS = 4
        private const val STATE = 5
        private const val OVERLAY_OFFSET = 6
        private const val PACKAGES = 7
        private const val A11Y_TREE_FULL = 8
        private const val VERSION = 9
        private const val STATE_FULL = 10
        private const val SOCKET_PORT = 11
        private const val OVERLAY_VISIBLE = 12
        private const val TOGGLE_WEBSOCKET_SERVER = 13
        private const val AUTH_TOKEN = 14
        private const val CONFIGURE_REVERSE_CONNECTION = 15
        private const val TOGGLE_PRODUCTION_MODE = 16
        private const val TOGGLE_SOCKET_SERVER = 17
        private const val TRIGGERS_CATALOG = 18
        private const val TRIGGERS_STATUS = 19
        private const val TRIGGERS_RULES = 20
        private const val TRIGGERS_RULE = 21
        private const val TRIGGERS_RUNS = 22
        private const val TRIGGERS_RULES_SAVE = 23
        private const val TRIGGERS_RULES_DELETE = 24
        private const val TRIGGERS_RULES_SET_ENABLED = 25
        private const val TRIGGERS_RULES_TEST = 26
        private const val TRIGGERS_RUNS_DELETE = 27
        private const val TRIGGERS_RUNS_CLEAR = 28
        private const val GETCLIPBOARD = 29
        private const val SETCLIPBOARD = 30

        private val uriMatcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(AUTHORITY, "a11y_tree", A11Y_TREE)
            addURI(AUTHORITY, "a11y_tree_full", A11Y_TREE_FULL)
            addURI(AUTHORITY, "phone_state", PHONE_STATE)
            addURI(AUTHORITY, "ping", PING)
            addURI(AUTHORITY, "keyboard/*", KEYBOARD_ACTIONS)
            addURI(AUTHORITY, "state", STATE)
            addURI(AUTHORITY, "state_full", STATE_FULL)
            addURI(AUTHORITY, "overlay_offset", OVERLAY_OFFSET)
            addURI(AUTHORITY, "packages", PACKAGES)
            addURI(AUTHORITY, "version", VERSION)
            addURI(AUTHORITY, "socket_port", SOCKET_PORT)
            addURI(AUTHORITY, "overlay_visible", OVERLAY_VISIBLE)
            addURI(AUTHORITY, "toggle_websocket_server", TOGGLE_WEBSOCKET_SERVER)
            addURI(AUTHORITY, "auth_token", AUTH_TOKEN)
            addURI(AUTHORITY, "configure_reverse_connection", CONFIGURE_REVERSE_CONNECTION)
            addURI(AUTHORITY, "toggle_production_mode", TOGGLE_PRODUCTION_MODE)
            addURI(AUTHORITY, "toggle_socket_server", TOGGLE_SOCKET_SERVER)
            addURI(AUTHORITY, "triggers/catalog", TRIGGERS_CATALOG)
            addURI(AUTHORITY, "triggers/status", TRIGGERS_STATUS)
            addURI(AUTHORITY, "triggers/rules", TRIGGERS_RULES)
            addURI(AUTHORITY, "triggers/rules/save", TRIGGERS_RULES_SAVE)
            addURI(AUTHORITY, "triggers/rules/delete", TRIGGERS_RULES_DELETE)
            addURI(AUTHORITY, "triggers/rules/set_enabled", TRIGGERS_RULES_SET_ENABLED)
            addURI(AUTHORITY, "triggers/rules/test", TRIGGERS_RULES_TEST)
            addURI(AUTHORITY, "triggers/rules/*", TRIGGERS_RULE)
            addURI(AUTHORITY, "triggers/runs", TRIGGERS_RUNS)
            addURI(AUTHORITY, "triggers/runs/delete", TRIGGERS_RUNS_DELETE)
            addURI(AUTHORITY, "triggers/runs/clear", TRIGGERS_RUNS_CLEAR)
            addURI(AUTHORITY, "getclipboard", GETCLIPBOARD)
            addURI(AUTHORITY, "setclipboard", SETCLIPBOARD)
        }
    }

    private lateinit var configManager: ConfigManager

    private var apiHandler: ApiHandler? = null

    override fun onCreate(): Boolean {
        val appContext = context?.applicationContext
        return if (appContext != null) {
            configManager = ConfigManager.getInstance(appContext)
            Log.d(TAG, "DroidrunContentProvider created")
            true
        } else {
            Log.e(TAG, "Failed to initialize: context is null")
            false
        }
    }

    private fun getAppVersion(): String {
        val appContext = context ?: return "unknown"
        return try {
            appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName
                ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }

    private fun getHandler(): ApiHandler? {
        if (apiHandler != null) return apiHandler

        val service = DroidrunAccessibilityService.getInstance()
        if (service != null && context != null) {
            apiHandler = ApiHandler(
                stateRepo = StateRepository(service),
                getKeyboardIME = { DroidrunKeyboardIME.getInstance() },
                getPackageManager = { context!!.packageManager },
                appVersionProvider = { getAppVersion() },
                context = context!!
            )
        }
        return apiHandler
    }

    private fun getTriggerApi(): TriggerApi? {
        val appContext = context?.applicationContext ?: return null
        return TriggerApi(appContext)
    }

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?
    ): Cursor {
        val cursor = MatrixCursor(arrayOf("result"))

        try {
            val match = uriMatcher.match(uri)
            val response = when (match) {
                VERSION -> ApiResponse.Success(getAppVersion())
                AUTH_TOKEN -> ApiResponse.Text(configManager.authToken)
                TRIGGERS_CATALOG,
                TRIGGERS_STATUS,
                TRIGGERS_RULES,
                TRIGGERS_RULE,
                TRIGGERS_RUNS,
                -> handleTriggerQuery(match, uri)
                else -> {
                    val handler = getHandler()
                    if (handler == null) {
                        ApiResponse.Error("Accessibility service not available")
                    } else {
                        when (match) {
                            A11Y_TREE -> handler.getTree()
                            A11Y_TREE_FULL -> handler.getTreeFull(
                                uri.getBooleanQueryParameter(
                                    "filter",
                                    true
                                )
                            )

                            PHONE_STATE -> handler.getPhoneState()
                            PING -> handler.ping()
                            STATE -> handler.getState()
                            STATE_FULL -> handler.getStateFull(
                                uri.getBooleanQueryParameter(
                                    "filter",
                                    true
                                )
                            )

                            PACKAGES -> handler.getPackages()
                            GETCLIPBOARD -> handler.getClipboard()
                            else -> ApiResponse.Error("Unknown endpoint: ${uri.path}")
                        }
                    }
                }
            }
            cursor.addRow(arrayOf(response.toJson()))

        } catch (e: Exception) {
            Log.e(TAG, "Query execution failed", e)
            cursor.addRow(arrayOf(ApiResponse.Error("Execution failed: ${e.message}").toJson()))
        }

        return cursor
    }

    private fun handleTriggerQuery(match: Int, uri: Uri): ApiResponse {
        val triggerApi = getTriggerApi() ?: return ApiResponse.Error("Trigger API unavailable")
        return when (match) {
            TRIGGERS_CATALOG -> ApiResponse.RawObject(triggerApi.catalog())
            TRIGGERS_STATUS -> ApiResponse.RawObject(triggerApi.status())
            TRIGGERS_RULES -> ApiResponse.RawArray(triggerApi.listRules())
            TRIGGERS_RULE -> mapTriggerResult(
                triggerApi.getRule(uri.lastPathSegment.orEmpty()),
            ) { ApiResponse.RawObject(it) }

            TRIGGERS_RUNS -> ApiResponse.RawArray(
                triggerApi.listRuns(uri.getQueryParameter("limit")?.toIntOrNull() ?: 50),
            )

            else -> ApiResponse.Error("Unknown trigger endpoint: ${uri.path}")
        }
    }

    private fun getStringValue(values: ContentValues?, key: String): String? {
        if (values == null) return null
        if (values.containsKey(key)) return values.getAsString(key)

        val base64Key = "${key}_base64"
        if (values.containsKey(base64Key)) {
            val encoded = values.getAsString(base64Key)
            return try {
                String(Base64.decode(encoded, Base64.DEFAULT))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to decode base64 for $key", e)
                null
            }
        }
        return null
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        val triggerResult = handleTriggerInsert(uri, values)
        if (triggerResult != null) {
            return mutationResultUri(triggerResult)
        }

        val handler = getHandler()
        if (handler == null) {
            return "content://$AUTHORITY/result?status=error&message=${Uri.encode("Accessibility service not available")}".toUri()
        }

        val result = try {
            val response = when (uriMatcher.match(uri)) {
                KEYBOARD_ACTIONS -> {
                    val action = uri.lastPathSegment
                    val vals = values ?: ContentValues()
                    when (action) {
                        "input" -> handler.keyboardInput(
                            vals.getAsString("base64_text") ?: "",
                            vals.getAsBoolean("clear") ?: true
                        )

                        "clear" -> handler.keyboardClear()
                        "key" -> handler.keyboardKey(vals.getAsInteger("key_code") ?: 0)
                        else -> ApiResponse.Error("Unknown keyboard action")
                    }
                }

                OVERLAY_OFFSET -> {
                    val offset = values?.getAsInteger("offset") ?: 0
                    handler.setOverlayOffset(offset)
                }

                SOCKET_PORT -> {
                    val port = values?.getAsInteger("port") ?: 0
                    handler.setSocketPort(port)
                }

                OVERLAY_VISIBLE -> {
                    val visible = values?.getAsBoolean("visible") ?: true
                    handler.setOverlayVisible(visible)
                }

                TOGGLE_SOCKET_SERVER -> {
                    val port = values?.getAsInteger("port") ?: configManager.socketServerPort
                    val enabled = values?.getAsBoolean("enabled") ?: true

                    if (values?.containsKey("port") == true) {
                        configManager.setSocketServerPortWithNotification(port)
                    }
                    configManager.setSocketServerEnabledWithNotification(enabled)

                    ApiResponse.Success("HTTP server ${if (enabled) "enabled" else "disabled"} on port $port")
                }

                TOGGLE_WEBSOCKET_SERVER -> {
                    val port = values?.getAsInteger("port") ?: configManager.websocketPort
                    val enabled = values?.getAsBoolean("enabled") ?: true

                    // Apply port change first so enabling starts on the requested port.
                    if (values?.containsKey("port") == true) {
                        configManager.setWebSocketPortWithNotification(port)
                    }
                    configManager.setWebSocketEnabledWithNotification(enabled)

                    ApiResponse.Success("WebSocket server ${if (enabled) "enabled" else "disabled"} on port $port")
                }

                CONFIGURE_REVERSE_CONNECTION -> {
                    val url = getStringValue(values, "url")
                    val token = getStringValue(values, "token")
                    val serviceKey = getStringValue(values, "service_key")
                    val enabled = values?.getAsBoolean("enabled")

                    var message = "Updated reverse connection config:"

                    if (url != null) {
                        configManager.reverseConnectionUrl = url
                        message += " url=$url"
                    }
                    if (token != null) {
                        configManager.reverseConnectionToken = token
                        message += " token=***"
                    }
                    if (serviceKey != null) {
                        configManager.reverseConnectionServiceKey = serviceKey
                        message += " service_key=***"
                    }
                    if (enabled != null) {
                        configManager.reverseConnectionEnabled = enabled
                        message += " enabled=$enabled"

                        val serviceIntent = android.content.Intent(
                            context,
                            com.droidrun.portal.service.ReverseConnectionService::class.java
                        )
                        if (enabled) {
                            context!!.startForegroundService(serviceIntent)
                        } else {
                            context!!.stopService(serviceIntent)
                        }
                    }

                    ApiResponse.Success(message)
                }

                TOGGLE_PRODUCTION_MODE -> {
                    val enabled = values?.getAsBoolean("enabled") ?: false
                    configManager.productionMode = enabled
                    val intent =
                        android.content.Intent("com.droidrun.portal.PRODUCTION_MODE_CHANGED")
                    context!!.sendBroadcast(intent)

                    ApiResponse.Success("Production mode set to $enabled")
                }

                SETCLIPBOARD -> {
                    val text = getStringValue(values, "text")
                        ?: return "content://$AUTHORITY/result?status=error&message=${Uri.encode("Missing required parameter: text or text_base64")}".toUri()
                    handler.setClipboard(text)
                }

                else -> ApiResponse.Error("Unsupported insert endpoint")
            }
            response
        } catch (e: Exception) {
            ApiResponse.Error("Exception: ${e.message}")
        }

        // Convert response to URI
        return if (result is ApiResponse.Success) {
            "content://$AUTHORITY/result?status=success&message=${Uri.encode(result.data.toString())}".toUri()
        } else {
            val errorMsg = (result as ApiResponse.Error).message
            "content://$AUTHORITY/result?status=error&message=${Uri.encode(errorMsg)}".toUri()
        }
    }

    private fun handleTriggerInsert(
        uri: Uri,
        values: ContentValues?,
    ): TriggerApiResult<*>? {
        val match = uriMatcher.match(uri)
        val triggerApi = getTriggerApi() ?: return when (match) {
            TRIGGERS_RULES_SAVE,
            TRIGGERS_RULES_DELETE,
            TRIGGERS_RULES_SET_ENABLED,
            TRIGGERS_RULES_TEST,
            TRIGGERS_RUNS_DELETE,
            TRIGGERS_RUNS_CLEAR,
            -> TriggerApiResult.Error("Trigger API unavailable")

            else -> null
        }
        return when (match) {
            TRIGGERS_RULES_SAVE -> {
                val ruleJson = getStringValue(values, "rule_json")
                    ?: return TriggerApiResult.Error("Missing required value: rule_json")
                triggerApi.saveRule(ruleJson)
            }

            TRIGGERS_RULES_DELETE -> {
                val ruleId = getStringValue(values, "rule_id")
                    ?: return TriggerApiResult.Error("Missing required value: rule_id")
                triggerApi.deleteRule(ruleId)
            }

            TRIGGERS_RULES_SET_ENABLED -> {
                val ruleId = getStringValue(values, "rule_id")
                    ?: return TriggerApiResult.Error("Missing required value: rule_id")
                val enabled = values?.getAsBoolean("enabled")
                    ?: return TriggerApiResult.Error("Missing required value: enabled")
                triggerApi.setRuleEnabled(ruleId, enabled)
            }

            TRIGGERS_RULES_TEST -> {
                val ruleId = getStringValue(values, "rule_id")
                    ?: return TriggerApiResult.Error("Missing required value: rule_id")
                triggerApi.testRule(ruleId)
            }

            TRIGGERS_RUNS_DELETE -> {
                val runId = getStringValue(values, "run_id")
                    ?: return TriggerApiResult.Error("Missing required value: run_id")
                triggerApi.deleteRun(runId)
            }

            TRIGGERS_RUNS_CLEAR -> triggerApi.clearRuns()
            else -> null
        }
    }

    private fun mutationResultUri(result: TriggerApiResult<*>): Uri {
        return when (result) {
            is TriggerApiResult.Error ->
                "content://$AUTHORITY/result?status=error&message=${Uri.encode(result.message)}".toUri()

            is TriggerApiResult.Success<*> -> {
                val message = result.message ?: "ok"
                "content://$AUTHORITY/result?status=success&message=${Uri.encode(message)}".toUri()
            }
        }
    }

    private fun <T> mapTriggerResult(
        result: TriggerApiResult<T>,
        onSuccess: (T) -> ApiResponse,
    ): ApiResponse {
        return when (result) {
            is TriggerApiResult.Error -> ApiResponse.Error(result.message)
            is TriggerApiResult.Success -> onSuccess(result.value)
        }
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<String>?
    ): Int = 0

    override fun getType(uri: Uri): String? = null
}
