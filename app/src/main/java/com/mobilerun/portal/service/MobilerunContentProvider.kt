package com.mobilerun.portal.service

import android.content.ContentProvider
import android.content.Context
import android.content.ContentValues
import android.content.Intent
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.util.Base64
import android.util.Log
import androidx.core.net.toUri
import com.mobilerun.portal.api.ApiHandler
import com.mobilerun.portal.api.ApiResponse
import com.mobilerun.portal.config.CloudTokenNormalizer
import com.mobilerun.portal.config.ConfigManager
import com.mobilerun.portal.core.StateRepository
import com.mobilerun.portal.input.MobilerunKeyboardIME
import com.mobilerun.portal.keepalive.KeepAliveController
import com.mobilerun.portal.keepalive.KeepAliveStartupException
import com.mobilerun.portal.state.ConnectionState
import com.mobilerun.portal.state.ConnectionStateManager
import com.mobilerun.portal.taskprompt.PortalCloudClient
import com.mobilerun.portal.taskprompt.PortalTaskLaunchCoordinator
import com.mobilerun.portal.taskprompt.PortalTaskLaunchMetadata
import com.mobilerun.portal.taskprompt.PortalTaskSettings
import com.mobilerun.portal.taskprompt.PortalTaskTracking
import com.mobilerun.portal.taskprompt.TaskPromptSettingsConstraints
import com.mobilerun.portal.triggers.TriggerApi
import com.mobilerun.portal.triggers.TriggerApiResult
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal fun handleKeepScreenAwakeInsert(
    providerContext: Context,
    enabled: Boolean,
): ApiResponse {
    return try {
        KeepAliveController.setEnabled(providerContext, enabled)
        ApiResponse.Success("Keep screen awake set to $enabled")
    } catch (e: KeepAliveStartupException) {
        ApiResponse.Error(e.reason)
    }
}

internal fun handleNoA11yModeInsert(
    providerContext: Context,
    configManager: ConfigManager,
    enabled: Boolean,
    accessibilityServiceAvailable: Boolean = MobilerunAccessibilityService.getInstance() != null,
    portalServiceRunning: Boolean = PortalService.getInstance() != null,
    startPortalService: (Context) -> Unit = { context ->
        context.startForegroundService(Intent(context, PortalService::class.java))
    },
    stopPortalService: (Context) -> Unit = { context ->
        context.stopService(Intent(context, PortalService::class.java))
    },
): ApiResponse {
    if (enabled) {
        if (accessibilityServiceAvailable) {
            return ApiResponse.Error("Disable AccessibilityService first")
        }
        configManager.noA11yMode = true
        if (!portalServiceRunning) {
            try {
                startPortalService(providerContext)
                Log.i("MobilerunContentProvider", "No-a11y mode enabled, PortalService starting")
            } catch (e: Exception) {
                Log.w(
                    "MobilerunContentProvider",
                    "startForegroundService failed (service may need manual start): ${e.message}",
                )
            }
        } else {
            Log.i("MobilerunContentProvider", "No-a11y mode enabled, PortalService already running")
        }
    } else {
        configManager.noA11yMode = false
        try {
            stopPortalService(providerContext)
            Log.i("MobilerunContentProvider", "No-a11y mode disabled, PortalService stopped")
        } catch (e: Exception) {
            Log.w("MobilerunContentProvider", "stopService failed: ${e.message}")
        }
    }
    return ApiResponse.Success("no_a11y_mode=$enabled")
}

internal fun ensurePortalServiceIfNoA11y(
    providerContext: Context?,
    configManager: ConfigManager,
    portalServiceRunning: Boolean = PortalService.getInstance() != null,
    startPortalService: (Context) -> Unit = { context ->
        context.startForegroundService(Intent(context, PortalService::class.java))
    },
) {
    ensureLocalServerHostAvailableForEnable(
        providerContext = providerContext,
        configManager = configManager,
        accessibilityServiceAvailable = false,
        portalServiceRunning = portalServiceRunning,
        startPortalService = startPortalService,
    )
}

internal fun ensureLocalServerHostAvailableForEnable(
    providerContext: Context?,
    configManager: ConfigManager,
    accessibilityServiceAvailable: Boolean = MobilerunAccessibilityService.getInstance() != null,
    portalServiceRunning: Boolean = PortalService.getInstance() != null,
    startPortalService: (Context) -> Unit = { context ->
        context.startForegroundService(Intent(context, PortalService::class.java))
    },
): ApiResponse? {
    if (accessibilityServiceAvailable) return null
    if (!configManager.noA11yMode) {
        return ApiResponse.Error("AccessibilityService or no-a11y mode required to enable local servers")
    }
    if (portalServiceRunning) return null
    val context = providerContext ?: return ApiResponse.Error("context unavailable")
    return try {
        startPortalService(context)
        Log.i("MobilerunContentProvider", "Restarted PortalService (no-a11y mode persisted)")
        null
    } catch (e: Exception) {
        Log.w("MobilerunContentProvider", "Could not restart PortalService: ${e.message}")
        ApiResponse.Error(e.message ?: "PortalService start failed")
    }
}

internal fun handleSocketServerToggleInsert(
    configManager: ConfigManager,
    values: ContentValues?,
    ensureLocalServerHost: () -> ApiResponse?,
): ApiResponse {
    val port = values?.getAsInteger("port") ?: configManager.socketServerPort
    val enabled = values?.getAsBoolean("enabled") ?: true
    val hasPort = values?.containsKey("port") == true
    val wasEnabled = configManager.socketServerEnabled
    val currentPort = configManager.socketServerPort

    if (enabled) {
        ensureLocalServerHost()?.let { return it }
        if (hasPort && port != currentPort) {
            if (wasEnabled) {
                configManager.setSocketServerPortWithNotification(port)
            } else {
                configManager.socketServerPort = port
            }
        }
        if (!wasEnabled) {
            configManager.setSocketServerEnabledWithNotification(true)
        }
    } else {
        if (wasEnabled) {
            configManager.setSocketServerEnabledWithNotification(false)
        }
        if (hasPort && port != currentPort) {
            configManager.socketServerPort = port
        }
    }
    return ApiResponse.Success("HTTP server ${if (enabled) "enabled" else "disabled"} on port $port")
}

internal fun handleWebSocketServerToggleInsert(
    configManager: ConfigManager,
    values: ContentValues?,
    ensureLocalServerHost: () -> ApiResponse?,
): ApiResponse {
    val port = values?.getAsInteger("port") ?: configManager.websocketPort
    val enabled = values?.getAsBoolean("enabled") ?: true
    val hasPort = values?.containsKey("port") == true
    val wasEnabled = configManager.websocketEnabled
    val currentPort = configManager.websocketPort

    if (enabled) {
        ensureLocalServerHost()?.let { return it }
        if (hasPort && port != currentPort) {
            if (wasEnabled) {
                configManager.setWebSocketPortWithNotification(port)
            } else {
                configManager.websocketPort = port
            }
        }
        if (!wasEnabled) {
            configManager.setWebSocketEnabledWithNotification(true)
        }
    } else {
        if (wasEnabled) {
            configManager.setWebSocketEnabledWithNotification(false)
        }
        if (hasPort && port != currentPort) {
            configManager.websocketPort = port
        }
    }
    return ApiResponse.Success("WebSocket server ${if (enabled) "enabled" else "disabled"} on port $port")
}

internal fun readProviderStringValue(
    values: ContentValues?,
    key: String,
    logTag: String,
): String? {
    if (values == null) return null
    if (values.containsKey(key)) return values.getAsString(key)

    val base64Key = "${key}_base64"
    if (values.containsKey(base64Key)) {
        val encoded = values.getAsString(base64Key)
        return try {
            String(Base64.decode(encoded, Base64.DEFAULT))
        } catch (e: Exception) {
            Log.e(logTag, "Failed to decode base64 for $key", e)
            null
        }
    }
    return null
}

internal fun interface CloudTaskLaunchInvoker {
    fun launch(
        prompt: String,
        settings: PortalTaskSettings,
        metadata: PortalTaskLaunchMetadata,
        skipBusyCheck: Boolean,
        memoryNamespace: String?,
        onComplete: (PortalTaskLaunchCoordinator.Result) -> Unit,
    )
}

internal fun handleCloudConnectInsert(
    providerContext: Context?,
    configManager: ConfigManager,
    values: ContentValues?,
    readStringValue: (ContentValues?, String) -> String? = { contentValues, key ->
        readProviderStringValue(contentValues, key, "MobilerunContentProvider")
    },
    beforeEnable: () -> Unit = {},
    startReverseConnectionService: (Context, String) -> Unit = { context, action ->
        context.startForegroundService(
            Intent(action, null, context, ReverseConnectionService::class.java),
        )
    },
): ApiResponse {
    val appContext = providerContext?.applicationContext
        ?: return ApiResponse.Error("context unavailable")
    val apiKey = sequenceOf("api_key", "token")
        .mapNotNull { key -> readStringValue(values, key) }
        .mapNotNull { value -> CloudTokenNormalizer.normalize(value) }
        .firstOrNull()
        ?: return ApiResponse.Error("Missing required value: api_key")
    val url = sequenceOf("url")
        .mapNotNull { key -> readStringValue(values, key)?.trim()?.takeIf { it.isNotBlank() } }
        .firstOrNull()
        ?: configManager.defaultReverseConnectionUrl

    if (PortalCloudClient.deriveRestBaseUrl(url) == null) {
        return ApiResponse.Error("Cloud connection URL must end in /v1/providers/personal/join")
    }

    return try {
        configManager.reverseConnectionUrl = url
        configManager.reverseConnectionToken = apiKey
        configManager.forceLoginOnNextConnect = false
        beforeEnable()
        configManager.reverseConnectionEnabled = true
        startReverseConnectionService(appContext, ReverseConnectionService.ACTION_RECONNECT)
        ApiResponse.Success("Cloud connection requested")
    } catch (e: Exception) {
        ApiResponse.Error("Could not start cloud connection: ${e.message}")
    }
}

internal fun buildCloudStatusResponse(
    configManager: ConfigManager,
    connectionStateProvider: () -> ConnectionState = { ConnectionStateManager.getState() },
): ApiResponse {
    val activeTask = configManager.activePortalTask
    val http402Snapshot = configManager.reverseJoinHttp402Snapshot()
    val connectionState = http402BlockedPresentationState(
        blocked = http402Snapshot.blocked,
        explicitlyDisconnected = http402Snapshot.explicitlyDisconnected,
    ) ?: connectionStateProvider()
    val json = JSONObject().apply {
        put("connectionState", connectionState.name)
        put("tokenPresent", configManager.reverseConnectionToken.trim().isNotEmpty())
        put("deviceId", configManager.deviceID)
        put("reverseConnectionUrl", configManager.reverseConnectionUrlOrDefault)
        if (activeTask != null) {
            put(
                "activeTask",
                JSONObject().apply {
                    put("task_id", activeTask.taskId)
                    put("taskId", activeTask.taskId)
                    put("promptPreview", activeTask.promptPreview)
                    put("status", activeTask.lastStatus)
                    put("startedAtMs", activeTask.startedAtMs)
                    put("pollDeadlineMs", activeTask.pollDeadlineMs)
                },
            )
        } else {
            put("activeTask", JSONObject.NULL)
        }
    }
    return ApiResponse.RawObject(json)
}

internal fun handleCloudTaskLaunchInsert(
    providerContext: Context?,
    configManager: ConfigManager,
    values: ContentValues?,
    readStringValue: (ContentValues?, String) -> String? = { contentValues, key ->
        readProviderStringValue(contentValues, key, "MobilerunContentProvider")
    },
    connectionStateProvider: () -> ConnectionState = { ConnectionStateManager.getState() },
    taskLaunchInvoker: CloudTaskLaunchInvoker,
    timeoutMs: Long = 30_000L,
): ApiResponse {
    if (providerContext?.applicationContext == null) {
        return ApiResponse.Error("context unavailable")
    }
    if (configManager.reverseConnectionToken.trim().isBlank()) {
        return ApiResponse.Error("Task launch requires a Mobilerun API key.")
    }
    if (PortalCloudClient.deriveRestBaseUrl(configManager.reverseConnectionUrlOrDefault) == null) {
        return ApiResponse.Error(
            "Task launch only supports WebSocket URLs ending in /v1/providers/personal/join.",
        )
    }
    if (connectionStateProvider() != ConnectionState.CONNECTED) {
        return ApiResponse.Error("Cloud connection is not connected")
    }

    val prompt = readStringValue(values, "prompt")?.trim().orEmpty()
    if (prompt.isBlank()) {
        return ApiResponse.Error("Missing required value: prompt")
    }

    val activeTask = configManager.activePortalTask
    if (activeTask != null && PortalTaskTracking.isBlockingStatus(activeTask.lastStatus)) {
        return ApiResponse.Error("A Mobilerun task is already running")
    }

    val settings = buildCloudTaskSettings(configManager.taskPromptSettings, values, readStringValue)
    val metadata = PortalTaskLaunchMetadata(
        returnToPortalOnTerminal = values?.getAsBoolean("return_to_portal")
            ?: configManager.taskPromptReturnToPortal,
    )
    val memoryNamespace = readStringValue(values, "memory_namespace")
        ?.trim()
        ?.takeIf { it.isNotBlank() }
    val latch = CountDownLatch(1)
    var launchResult: PortalTaskLaunchCoordinator.Result? = null

    try {
        taskLaunchInvoker.launch(
            prompt,
            settings,
            metadata,
            false,
            memoryNamespace,
        ) { result ->
            launchResult = result
            latch.countDown()
        }
    } catch (e: Exception) {
        return ApiResponse.Error("Could not launch Mobilerun task: ${e.message}")
    }

    if (!latch.await(timeoutMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)) {
        return ApiResponse.Error("Timed out waiting for Mobilerun task launch")
    }

    return when (val result = launchResult) {
        is PortalTaskLaunchCoordinator.Result.Success -> ApiResponse.RawObject(
            JSONObject().apply {
                put("task_id", result.record.taskId)
                put("taskId", result.record.taskId)
                put("status", result.record.lastStatus)
                put("promptPreview", result.record.promptPreview)
            },
        )

        is PortalTaskLaunchCoordinator.Result.Error -> ApiResponse.Error(result.message)
        PortalTaskLaunchCoordinator.Result.Busy -> ApiResponse.Error("A Mobilerun task is already running")
        null -> ApiResponse.Error("Mobilerun task launch returned no result")
    }
}

internal fun handleReverseConnectionConfigInsert(
    providerContext: Context?,
    configManager: ConfigManager,
    values: ContentValues?,
    readStringValue: (ContentValues?, String) -> String? = { contentValues, key ->
        readProviderStringValue(contentValues, key, "MobilerunContentProvider")
    },
    startReverseConnectionService: (Context, String) -> Unit = { context, action ->
        context.startForegroundService(
            Intent(action, null, context, ReverseConnectionService::class.java),
        )
    },
    stopReverseConnectionService: (Context, Intent) -> Unit = { context, intent ->
        context.stopService(intent)
    },
    publishConnectionState: (ConnectionState) -> Unit = ConnectionStateManager::setState,
): ApiResponse {
    return try {
        val url = readStringValue(values, "url")
        val token = readStringValue(values, "token")
        val serviceKey = readStringValue(values, "service_key")
        val enabled = values?.getAsBoolean("enabled")

        var message = "Updated reverse connection config:"
        var connectionConfigChanged = false

        if (url != null) {
            val effectiveUrl = url.ifBlank { configManager.defaultReverseConnectionUrl }
            connectionConfigChanged =
                connectionConfigChanged || effectiveUrl != configManager.reverseConnectionUrlOrDefault
            configManager.reverseConnectionUrl = url
            message += " url=$url"
        }
        if (token != null) {
            val normalizedToken = CloudTokenNormalizer.normalize(token).orEmpty()
            connectionConfigChanged =
                connectionConfigChanged || normalizedToken != configManager.reverseConnectionToken
            configManager.reverseConnectionToken = token
            message += " token=***"
        }
        if (serviceKey != null) {
            connectionConfigChanged =
                connectionConfigChanged || serviceKey != configManager.reverseConnectionServiceKey
            configManager.reverseConnectionServiceKey = serviceKey
            message += " service_key=***"
        }
        if (enabled != null) {
            configManager.reverseConnectionEnabled = enabled
            message += " enabled=$enabled"
        }

        val shouldReconnect = enabled == true ||
            (enabled == null && connectionConfigChanged && configManager.reverseConnectionEnabled)
        if (shouldReconnect || enabled == false) {
            val appContext = providerContext?.applicationContext
                ?: throw IllegalStateException("context unavailable")
            if (shouldReconnect) {
                startReverseConnectionService(
                    appContext,
                    ReverseConnectionService.ACTION_RECONNECT,
                )
            } else {
                val serviceIntent = Intent(appContext, ReverseConnectionService::class.java)
                performExplicitReverseConnectionDisconnect(
                    markExplicitlyDisconnected =
                        configManager::markReverseJoinExplicitlyDisconnected,
                    publishDisconnected = publishConnectionState,
                    dispatchDisconnect = {
                        stopReverseConnectionService(appContext, serviceIntent)
                    },
                )
            }
        }

        ApiResponse.Success(message)
    } catch (e: Exception) {
        ApiResponse.Error("Exception: ${e.message}")
    }
}

private fun buildCloudTaskSettings(
    defaultSettings: PortalTaskSettings,
    values: ContentValues?,
    readStringValue: (ContentValues?, String) -> String?,
): PortalTaskSettings {
    var settings = defaultSettings
    readStringValue(values, "llm_model")
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.let { settings = settings.copy(llmModel = it) }
    values?.getAsBoolean("reasoning")?.let { settings = settings.copy(reasoning = it) }
    values?.getAsBoolean("vision")?.let { settings = settings.copy(vision = it) }
    values?.getAsInteger("max_steps")?.let { settings = settings.copy(maxSteps = it) }
    values?.getAsDouble("temperature")?.let { settings = settings.copy(temperature = it) }
    values?.getAsInteger("execution_timeout")?.let {
        settings = settings.copy(executionTimeout = it)
    }
    return TaskPromptSettingsConstraints.clamp(settings)
}

class MobilerunContentProvider : ContentProvider() {
    companion object {
        private const val TAG = "MobilerunContentProvider"
        private const val AUTHORITY = "com.mobilerun.portal"
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
        private const val TOGGLE_SCREEN_KEEP_AWAKE = 29
        private const val SCREEN_KEEP_AWAKE_STATUS = 30
        private const val SET_NO_A11Y_MODE = 31
        private const val CLIPBOARD_GET = 32
        private const val CLIPBOARD_SET = 33
        private const val CLOUD_CONNECT = 34
        private const val CLOUD_STATUS = 35
        private const val CLOUD_TASKS_LAUNCH = 36

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
            addURI(AUTHORITY, "toggle_screen_keep_awake", TOGGLE_SCREEN_KEEP_AWAKE)
            addURI(AUTHORITY, "screen_keep_awake_status", SCREEN_KEEP_AWAKE_STATUS)
            addURI(AUTHORITY, "set_no_a11y_mode", SET_NO_A11Y_MODE)
            addURI(AUTHORITY, "clipboard/get", CLIPBOARD_GET)
            addURI(AUTHORITY, "clipboard/set", CLIPBOARD_SET)
            addURI(AUTHORITY, "cloud/connect", CLOUD_CONNECT)
            addURI(AUTHORITY, "cloud/status", CLOUD_STATUS)
            addURI(AUTHORITY, "cloud/tasks/launch", CLOUD_TASKS_LAUNCH)
        }
    }

    private lateinit var configManager: ConfigManager

    private val apiHandlerCache = ServiceInstanceCache<MobilerunAccessibilityService, ApiHandler>()

    override fun onCreate(): Boolean {
        val appContext = context?.applicationContext
        return if (appContext != null) {
            configManager = ConfigManager.getInstance(appContext)
            Log.d(TAG, "MobilerunContentProvider created")
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
        val service = MobilerunAccessibilityService.getInstance()
        val providerContext = context
        if (service == null || providerContext == null) {
            apiHandlerCache.clear()
            return null
        }

        return apiHandlerCache.get(service) { currentService ->
            ApiHandler(
                stateRepo = StateRepository(currentService),
                getKeyboardIME = { MobilerunKeyboardIME.getInstance() },
                getPackageManager = { providerContext.packageManager },
                appVersionProvider = { getAppVersion() },
                context = providerContext
            )
        }
    }

    private fun getHeadlessCapableHandler(): ApiHandler? {
        val providerContext = context ?: return null
        return MobilerunAccessibilityService.getInstance()?.let {
            getHandler()
        } ?: ApiHandler(
            stateRepo = StateRepository(service = null),
            getKeyboardIME = { MobilerunKeyboardIME.getInstance() },
            getPackageManager = { providerContext.packageManager },
            appVersionProvider = { getAppVersion() },
            context = providerContext,
        )
    }

    private fun getTriggerApi(): TriggerApi? {
        val appContext = context?.applicationContext ?: return null
        return TriggerApi(appContext)
    }

    private fun enforceAuthorizedCaller() {
        val appContext = context?.applicationContext
            ?: throw SecurityException("Provider context unavailable")
        val callingUid = Binder.getCallingUid()
        val appUid = appContext.applicationInfo.uid
        if (!ContentProviderAccessPolicy.isUidAllowed(callingUid, appUid)) {
            Log.w(TAG, "Rejected content provider call from uid=$callingUid")
            throw SecurityException("Caller uid $callingUid is not allowed")
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?
    ): Cursor {
        enforceAuthorizedCaller()
        val cursor = MatrixCursor(arrayOf("result"))

        try {
            val match = uriMatcher.match(uri)
            val response = when (match) {
                VERSION -> ApiResponse.Success(getAppVersion())
                AUTH_TOKEN -> ApiResponse.Text(configManager.authToken)
                CLOUD_STATUS -> buildCloudStatusResponse(configManager)
                SCREEN_KEEP_AWAKE_STATUS -> ApiResponse.RawObject(
                    KeepAliveController.getStatusJson(context ?: throw IllegalStateException("Provider context unavailable")),
                )
                CLIPBOARD_GET -> {
                    val handler = getHeadlessCapableHandler()
                    handler?.getClipboard() ?: ApiResponse.Error("Provider context unavailable")
                }
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
        return readProviderStringValue(values, key, TAG)
    }

    private fun handleConfigureReverseConnectionInsert(values: ContentValues?): ApiResponse {
        return handleReverseConnectionConfigInsert(
            providerContext = context,
            configManager = configManager,
            values = values,
            readStringValue = ::getStringValue,
        )
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        enforceAuthorizedCaller()
        val match = uriMatcher.match(uri)
        val triggerResult = handleTriggerInsert(uri, values)
        if (triggerResult != null) {
            return mutationResultUri(triggerResult)
        }

        if (match == CLOUD_CONNECT) {
            val response = handleCloudConnectInsert(
                providerContext = context,
                configManager = configManager,
                values = values,
            )
            return responseToResultUri(response)
        }

        if (match == CLOUD_TASKS_LAUNCH) {
            val providerContext = context
            val response = handleCloudTaskLaunchInsert(
                providerContext = providerContext,
                configManager = configManager,
                values = values,
                taskLaunchInvoker = CloudTaskLaunchInvoker { prompt, settings, metadata, skipBusyCheck, memoryNamespace, onComplete ->
                    if (providerContext == null) {
                        onComplete(PortalTaskLaunchCoordinator.Result.Error("context unavailable"))
                    } else {
                        PortalTaskLaunchCoordinator(providerContext).launchPrompt(
                            prompt = prompt,
                            settings = settings,
                            broadcastTaskStateChanged = true,
                            metadata = metadata,
                            skipBusyCheck = skipBusyCheck,
                            memoryNamespace = memoryNamespace,
                            onComplete = onComplete,
                        )
                    }
                },
            )
            return cloudTaskLaunchResultUri(response)
        }

        if (match == CONFIGURE_REVERSE_CONNECTION) {
            return responseToResultUri(handleConfigureReverseConnectionInsert(values))
        }

        if (match == SET_NO_A11Y_MODE) {
            val enabled = values?.getAsBoolean("enabled") ?: true
            val response = handleNoA11yModeInsert(
                providerContext = context
                    ?: return "content://$AUTHORITY/result?status=error&message=${Uri.encode("context unavailable")}".toUri(),
                configManager = configManager,
                enabled = enabled,
            )
            return responseToResultUri(response)
        }

        if (match == TOGGLE_SOCKET_SERVER) {
            val response = handleSocketServerToggleInsert(
                configManager = configManager,
                values = values,
                ensureLocalServerHost = {
                    ensureLocalServerHostAvailableForEnable(context, configManager)
                },
            )
            return responseToResultUri(response)
        }

        if (match == TOGGLE_WEBSOCKET_SERVER) {
            val response = handleWebSocketServerToggleInsert(
                configManager = configManager,
                values = values,
                ensureLocalServerHost = {
                    ensureLocalServerHostAvailableForEnable(context, configManager)
                },
            )
            return responseToResultUri(response)
        }

        if (match == TOGGLE_SCREEN_KEEP_AWAKE) {
            val enabled = values?.getAsBoolean("enabled")
                ?: return "content://$AUTHORITY/result?status=error&message=${Uri.encode("Missing required field: enabled")}".toUri()
            val response = handleKeepScreenAwakeInsert(
                context ?: throw IllegalStateException("Provider context unavailable"),
                enabled,
            )
            return responseToResultUri(response)
        }

        if (match == CLIPBOARD_SET) {
            val text = getStringValue(values, "text")
                ?: return "content://$AUTHORITY/result?status=error&message=${Uri.encode("Missing required value: text")}".toUri()
            val response = getHeadlessCapableHandler()?.setClipboard(text)
                ?: ApiResponse.Error("Provider context unavailable")
            return responseToResultUri(response)
        }

        val handler = getHandler()
        if (handler == null) {
            return "content://$AUTHORITY/result?status=error&message=${Uri.encode("Accessibility service not available")}".toUri()
        }

        val result = try {
            val response = when (match) {
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

                TOGGLE_PRODUCTION_MODE -> {
                    val enabled = values?.getAsBoolean("enabled") ?: false
                    configManager.productionMode = enabled
                    val intent =
                        android.content.Intent("com.mobilerun.portal.PRODUCTION_MODE_CHANGED")
                    context!!.sendBroadcast(intent)

                    ApiResponse.Success("Production mode set to $enabled")
                }

                else -> ApiResponse.Error("Unsupported insert endpoint")
            }
            response
        } catch (e: Exception) {
            ApiResponse.Error("Exception: ${e.message}")
        }

        // Convert response to URI
        return responseToResultUri(result)
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

    private fun responseToResultUri(response: ApiResponse): Uri {
        return when (response) {
            is ApiResponse.Success ->
                "content://$AUTHORITY/result?status=success&message=${Uri.encode(response.data.toString())}".toUri()

            is ApiResponse.Error ->
                "content://$AUTHORITY/result?status=error&message=${Uri.encode(response.message)}".toUri()

            else ->
                "content://$AUTHORITY/result?status=error&message=${Uri.encode("Unsupported response type")}".toUri()
        }
    }

    private fun cloudTaskLaunchResultUri(response: ApiResponse): Uri {
        if (response !is ApiResponse.RawObject) {
            return responseToResultUri(response)
        }
        val taskId = response.json.optString("task_id", "")
        val builder = Uri.Builder()
            .scheme("content")
            .authority(AUTHORITY)
            .path("result")
            .appendQueryParameter("status", "success")
            .appendQueryParameter("message", "Task launched")
        if (taskId.isNotBlank()) {
            builder.appendQueryParameter("task_id", taskId)
        }
        return builder.build()
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

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int {
        enforceAuthorizedCaller()
        return 0
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<String>?
    ): Int {
        enforceAuthorizedCaller()
        return 0
    }

    override fun getType(uri: Uri): String? = null
}
