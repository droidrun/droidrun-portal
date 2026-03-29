package com.droidrun.portal.taskprompt

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.util.LinkedHashSet
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class PortalTaskSettings(
    val llmModel: String = PortalCloudClient.DEFAULT_MODEL_ID,
    val reasoning: Boolean = PortalCloudClient.DEFAULT_REASONING,
    val vision: Boolean = PortalCloudClient.DEFAULT_VISION,
    val maxSteps: Int = PortalCloudClient.DEFAULT_MAX_STEPS,
    val temperature: Double = PortalCloudClient.DEFAULT_TEMPERATURE,
    val executionTimeout: Int = PortalCloudClient.DEFAULT_EXECUTION_TIMEOUT,
)

data class PortalTaskDraft(
    val prompt: String,
    val settings: PortalTaskSettings,
    val returnToPortalOnTerminal: Boolean = false,
)

data class PortalModelOption(
    val id: String,
    val label: String,
)

data class PortalModelsLoadResult(
    val models: List<PortalModelOption>,
    val warningMessage: String? = null,
    val loadedFromServer: Boolean = false,
)

data class PortalTaskLaunchSuccess(
    val taskId: String,
)

data class PortalBalanceInfo(
    val balance: Int,
    val usage: Int,
    val nextReset: String?,
)

data class PortalTaskStatusSuccess(
    val status: String,
)

sealed class PortalTaskLaunchResult {
    data class Success(val value: PortalTaskLaunchSuccess) : PortalTaskLaunchResult()
    data class Error(val message: String) : PortalTaskLaunchResult()
}

sealed class PortalBalanceResult {
    data class Success(val value: PortalBalanceInfo) : PortalBalanceResult()
    data class Error(
        val message: String,
        val retryable: Boolean = false,
    ) : PortalBalanceResult()
    data class Unavailable(val message: String? = null) : PortalBalanceResult()
}

sealed class PortalTaskStatusResult {
    data class Success(val value: PortalTaskStatusSuccess) : PortalTaskStatusResult()
    data class Error(val message: String) : PortalTaskStatusResult()
}

sealed class PortalTaskDetailsResult {
    data class Success(val value: PortalTaskDetails) : PortalTaskDetailsResult()
    data class Error(val message: String) : PortalTaskDetailsResult()
}

sealed class PortalTaskHistoryResult {
    data class Success(val value: PortalTaskHistoryPage) : PortalTaskHistoryResult()
    data class Error(val message: String) : PortalTaskHistoryResult()
}

sealed class PortalTaskScreenshotResult {
    data class Success(val value: PortalTaskScreenshotSet) : PortalTaskScreenshotResult()
    data class Error(val message: String) : PortalTaskScreenshotResult()
}

sealed class PortalTaskTrajectoryResult {
    data class Success(val value: PortalTaskTrajectorySet) : PortalTaskTrajectoryResult()
    data class Error(val message: String) : PortalTaskTrajectoryResult()
}

sealed class PortalTaskCancelResult {
    object Success : PortalTaskCancelResult()
    object AlreadyFinished : PortalTaskCancelResult()
    data class Error(val message: String) : PortalTaskCancelResult()
}

class PortalCloudClient(
    private val okHttpClient: OkHttpClient = OkHttpClient(),
) {
    companion object {
        private const val TAG = "PortalCloudClient"
        const val DEFAULT_MODEL_ID = "google/gemini-3.1-flash-lite-preview"
        const val DEFAULT_REASONING = false
        const val DEFAULT_VISION = false
        const val DEFAULT_MAX_STEPS = 100
        const val DEFAULT_TEMPERATURE = 0.5
        const val DEFAULT_EXECUTION_TIMEOUT = 1000
        internal const val LAUNCH_RECOVERY_WINDOW_MS = 8_000L
        internal const val LAUNCH_RECOVERY_RETRY_INTERVAL_MS = 1_000L

        private const val SUPPORTED_JOIN_PATH = "/v1/providers/personal/join"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val HARD_LAUNCH_FAILURE_CODES = setOf(400, 401, 403, 404, 412, 422)
        private val LAUNCH_RECOVERY_EXECUTOR = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "PortalLaunchRecovery").apply {
                isDaemon = true
            }
        }
        private val FALLBACK_MODEL_IDS = listOf(
            DEFAULT_MODEL_ID,
            "google/gemini-3.1-pro-preview",
            "google/gemini-3.1-flash-lite-preview",
            "openai/gpt-5.4",
            "openai/gpt-5.4-pro",
            "qwen/qwen3.5-35b",
            "moonshotai/kimi-k2.5",
            "anthropic/claude-sonnet-4.6",
            "anthropic/claude-opus-4.6",
            "mobilerun/mobile-agent-fast",
            "mobilerun/mobile-agent-thinking",
        )

        fun fallbackModelOptions(): List<PortalModelOption> = buildModelOptions(FALLBACK_MODEL_IDS)

        fun deriveRestBaseUrl(reverseConnectionUrl: String): String? {
            if (reverseConnectionUrl.isBlank()) return null

            return try {
                val normalizedUrl = reverseConnectionUrl.trim().replace("{deviceId}", "device")
                val uri = URI(normalizedUrl)
                val scheme = when (uri.scheme?.lowercase(Locale.US)) {
                    "ws" -> "http"
                    "wss" -> "https"
                    else -> return null
                }
                val normalizedPath = uri.path?.trimEnd('/') ?: return null
                if (normalizedPath != SUPPORTED_JOIN_PATH) {
                    return null
                }

                buildString {
                    append(scheme)
                    append("://")
                    append(uri.host ?: return null)
                    if (uri.port != -1) {
                        append(":")
                        append(uri.port)
                    }
                    append("/v1")
                }
            } catch (_: Exception) {
                null
            }
        }

        fun deriveCloudBaseUrl(reverseConnectionUrl: String): String? {
            if (reverseConnectionUrl.isBlank()) return null

            return try {
                val normalizedUrl = reverseConnectionUrl.trim().replace("{deviceId}", "device")
                val uri = URI(normalizedUrl)
                when (uri.scheme?.lowercase(Locale.US)) {
                    "ws", "wss" -> Unit
                    else -> return null
                }

                val normalizedPath = uri.path?.trimEnd('/') ?: return null
                if (normalizedPath != SUPPORTED_JOIN_PATH) {
                    return null
                }

                val host = uri.host?.trim().orEmpty()
                if (host.isBlank()) {
                    return null
                }

                val cloudHost = when {
                    host.startsWith("api.", ignoreCase = true) -> "cloud.${host.removePrefix("api.")}"
                    host.startsWith("cloud.", ignoreCase = true) -> host
                    else -> return null
                }

                buildString {
                    append("https://")
                    append(cloudHost)
                    if (uri.port != -1) {
                        append(":")
                        append(uri.port)
                    }
                }
            } catch (_: Exception) {
                null
            }
        }

        fun normalizeModelIds(body: String): List<String> {
            val normalized = LinkedHashSet<String>()
            val trimmedBody = body.trim()
            if (trimmedBody.isEmpty()) return emptyList()

            if (trimmedBody.startsWith("{")) {
                val jsonObject = JSONObject(trimmedBody)
                if (jsonObject.has("data")) {
                    collectModelIds(jsonObject.optJSONArray("data"), normalized)
                }
            } else if (trimmedBody.startsWith("[")) {
                collectModelIds(JSONArray(trimmedBody), normalized)
            }

            return normalized.toList()
        }

        fun buildModelOptions(modelIds: List<String>): List<PortalModelOption> {
            return modelIds
                .distinct()
                .map { PortalModelOption(id = it, label = formatModelLabel(it)) }
        }

        internal fun parseLaunchTaskId(body: String): String? {
            val trimmedBody = body.trim()
            if (!trimmedBody.startsWith("{")) return null

            return try {
                val json = JSONObject(trimmedBody)
                firstNonBlankString(
                    json,
                    "id",
                    "taskId",
                    "task_id",
                ) ?: json.optJSONObject("task")?.let { task ->
                    firstNonBlankString(task, "id", "taskId", "task_id")
                } ?: json.optJSONObject("data")?.let { data ->
                    firstNonBlankString(data, "id", "taskId", "task_id")
                        ?: data.optJSONObject("task")?.let { task ->
                            firstNonBlankString(task, "id", "taskId", "task_id")
                        }
                } ?: json.optJSONObject("result")?.let { result ->
                    firstNonBlankString(result, "id", "taskId", "task_id")
                        ?: result.optJSONObject("task")?.let { task ->
                            firstNonBlankString(task, "id", "taskId", "task_id")
                        }
                }
            } catch (_: Exception) {
                null
            }
        }

        internal fun hasLaunchRecoveryTimeRemaining(
            launchStartedAtMs: Long,
            nowMs: Long = System.currentTimeMillis(),
        ): Boolean {
            return nowMs < launchStartedAtMs + LAUNCH_RECOVERY_WINDOW_MS
        }

        internal fun nextLaunchRecoveryDelayMs(
            launchStartedAtMs: Long,
            nowMs: Long = System.currentTimeMillis(),
        ): Long {
            val remainingMs = (launchStartedAtMs + LAUNCH_RECOVERY_WINDOW_MS) - nowMs
            if (remainingMs <= 0L) return 0L
            return remainingMs.coerceAtMost(LAUNCH_RECOVERY_RETRY_INTERVAL_MS)
        }

        internal fun findRecoverableTaskId(
            page: PortalTaskHistoryPage?,
            deviceId: String,
            prompt: String,
            launchStartedAtMs: Long,
            nowMs: Long = System.currentTimeMillis(),
        ): String? {
            val normalizedPrompt = normalizePrompt(prompt)
            if (normalizedPrompt.isBlank()) return null

            val lowerBoundMs = launchStartedAtMs - 5_000L
            val upperBoundMs = nowMs + 5_000L
            val recentCandidates = page?.items
                ?.filter { item ->
                    val createdAtMs = parseCreatedAtMs(item.createdAt) ?: return@filter false
                    createdAtMs in lowerBoundMs..upperBoundMs &&
                        nowMs - createdAtMs <= 30_000L
                }
                .orEmpty()
            if (recentCandidates.isEmpty()) return null

            val normalizedDeviceId = deviceId.trim().lowercase(Locale.US)
            val sameDeviceCandidates = recentCandidates.filter { item ->
                val itemDeviceId = item.deviceId?.trim()?.lowercase(Locale.US).orEmpty()
                normalizedDeviceId.isNotBlank() && itemDeviceId == normalizedDeviceId
            }

            val scopedCandidates = when {
                sameDeviceCandidates.isNotEmpty() -> sameDeviceCandidates
                recentCandidates.none { !it.deviceId.isNullOrBlank() } -> recentCandidates
                else -> emptyList()
            }
            if (scopedCandidates.isEmpty()) return null

            return scopedCandidates
                .firstExactPromptMatch(normalizedPrompt)
                ?: scopedCandidates.firstPreviewOrPrefixMatch(normalizedPrompt)
                ?: scopedCandidates.singleOrNull()?.taskId
        }

        private fun normalizePrompt(prompt: String): String {
            return prompt.trim().replace(Regex("\\s+"), " ")
        }

        private fun List<PortalTaskHistoryItem>.firstExactPromptMatch(
            normalizedPrompt: String,
        ): String? {
            return firstOrNull { item ->
                normalizePrompt(item.prompt) == normalizedPrompt
            }?.taskId
        }

        private fun List<PortalTaskHistoryItem>.firstPreviewOrPrefixMatch(
            normalizedPrompt: String,
        ): String? {
            return firstOrNull { item ->
                val candidatePrompt = normalizePrompt(item.prompt)
                val candidatePreview = normalizePrompt(
                    item.promptPreview.removeSuffix("…").removeSuffix("..."),
                )
                when {
                    candidatePrompt.isNotBlank() &&
                        (candidatePrompt.startsWith(normalizedPrompt) ||
                            normalizedPrompt.startsWith(candidatePrompt)) -> true

                    candidatePreview.isNotBlank() &&
                        normalizedPrompt.startsWith(candidatePreview) -> true

                    else -> false
                }
            }?.taskId
        }

        private fun parseCreatedAtMs(createdAt: String?): Long? {
            return PortalTaskTimestampSupport.parseEpochMillis(createdAt)
        }

        fun formatModelLabel(modelId: String): String {
            val modelName = modelId.substringAfter('/')
            return modelName
                .split('-')
                .filter { it.isNotBlank() }
                .joinToString(" ") { segment ->
                    when {
                        segment.all { it.isDigit() } -> segment
                        segment.matches(Regex("""\d+\.\d+""")) -> segment
                        segment.length <= 4 && segment.all { it.isLetter() } -> {
                            segment.uppercase(Locale.US)
                        }

                        else -> segment.replaceFirstChar {
                            if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString()
                        }
                    }
                }
        }

        fun buildTaskPayload(deviceId: String, draft: PortalTaskDraft): JSONObject {
            return JSONObject().apply {
                put("deviceId", deviceId)
                put("task", draft.prompt)
                put("llmModel", draft.settings.llmModel)
                put("reasoning", draft.settings.reasoning)
                put("vision", draft.settings.vision)
                put("maxSteps", draft.settings.maxSteps)
                put("temperature", draft.settings.temperature)
                put("executionTimeout", draft.settings.executionTimeout)
                put("displayId", 0)
            }
        }

        fun buildModelsRequest(restBaseUrl: String, authToken: String): Request {
            return Request.Builder()
                .url("${restBaseUrl.trimEnd('/')}/models")
                .addHeader("Authorization", "Bearer $authToken")
                .get()
                .build()
        }

        fun buildLaunchTaskRequest(
            restBaseUrl: String,
            authToken: String,
            deviceId: String,
            draft: PortalTaskDraft,
        ): Request {
            val payload = buildTaskPayload(deviceId, draft).toString()
            return Request.Builder()
                .url("${restBaseUrl.trimEnd('/')}/tasks")
                .addHeader("Authorization", "Bearer $authToken")
                .addHeader("Content-Type", "application/json")
                .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                .build()
        }

        fun buildTaskStatusRequest(
            restBaseUrl: String,
            authToken: String,
            taskId: String,
        ): Request {
            return Request.Builder()
                .url("${restBaseUrl.trimEnd('/')}/tasks/$taskId/status")
                .addHeader("Authorization", "Bearer $authToken")
                .get()
                .build()
        }

        fun buildBalanceRequest(
            cloudBaseUrl: String,
            authToken: String,
        ): Request {
            return Request.Builder()
                .url("${cloudBaseUrl.trimEnd('/')}/api/billing/balance")
                .addHeader("Authorization", "Bearer $authToken")
                .get()
                .build()
        }

        fun buildListTasksRequest(
            restBaseUrl: String,
            authToken: String,
            query: String?,
            page: Int,
            pageSize: Int,
        ): Request {
            val url = "${restBaseUrl.trimEnd('/')}/tasks".toHttpUrl().newBuilder()
                .addQueryParameter("orderBy", "createdAt")
                .addQueryParameter("orderByDirection", "desc")
                .addQueryParameter("page", page.coerceAtLeast(1).toString())
                .addQueryParameter("pageSize", pageSize.coerceIn(1, 100).toString())
                .apply {
                    query?.trim()?.takeIf { it.isNotBlank() }?.let {
                        addQueryParameter("query", it)
                    }
                }
                .build()

            return Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $authToken")
                .get()
                .build()
        }

        fun buildTaskDetailsRequest(
            restBaseUrl: String,
            authToken: String,
            taskId: String,
        ): Request {
            return Request.Builder()
                .url("${restBaseUrl.trimEnd('/')}/tasks/$taskId")
                .addHeader("Authorization", "Bearer $authToken")
                .get()
                .build()
        }

        fun buildTaskScreenshotsRequest(
            restBaseUrl: String,
            authToken: String,
            taskId: String,
        ): Request {
            return Request.Builder()
                .url("${restBaseUrl.trimEnd('/')}/tasks/$taskId/screenshots")
                .addHeader("Authorization", "Bearer $authToken")
                .get()
                .build()
        }

        fun buildTaskTrajectoryRequest(
            restBaseUrl: String,
            authToken: String,
            taskId: String,
        ): Request {
            return Request.Builder()
                .url("${restBaseUrl.trimEnd('/')}/tasks/$taskId/trajectory")
                .addHeader("Authorization", "Bearer $authToken")
                .get()
                .build()
        }

        fun buildCancelTaskRequest(
            restBaseUrl: String,
            authToken: String,
            taskId: String,
        ): Request {
            return Request.Builder()
                .url("${restBaseUrl.trimEnd('/')}/tasks/$taskId/cancel")
                .addHeader("Authorization", "Bearer $authToken")
                .addHeader("Content-Type", "application/json")
                .post("{}".toRequestBody(JSON_MEDIA_TYPE))
                .build()
        }

        private fun collectModelIds(array: JSONArray?, sink: LinkedHashSet<String>) {
            if (array == null) return
            for (index in 0 until array.length()) {
                when (val item = array.opt(index)) {
                    is String -> {
                        val value = item.trim()
                        if (value.isNotEmpty()) sink.add(value)
                    }

                    is JSONObject -> {
                        val value = item.optString("id").trim()
                        if (value.isNotEmpty()) sink.add(value)
                    }
                }
            }
        }

        private fun parseErrorDetail(body: String?): String? {
            if (body.isNullOrBlank()) return null

            return try {
                if (body.trim().startsWith("{")) {
                    val json = JSONObject(body)
                    listOf("detail", "message", "error", "title")
                        .mapNotNull { key -> json.optString(key).takeIf { it.isNotBlank() } }
                        .firstOrNull()
                } else {
                    body.trim()
                }
            } catch (_: Exception) {
                body.trim()
            }
        }

        private fun parseTaskStatus(body: String): String? {
            return try {
                JSONObject(body).optString("status").trim().takeIf { it.isNotBlank() }
            } catch (_: Exception) {
                null
            }
        }

        fun parseBalanceInfo(body: String): PortalBalanceInfo? {
            return try {
                val root = JSONObject(body)
                PortalBalanceInfo(
                    balance = if (root.has("balance") && !root.isNull("balance")) root.optInt("balance") else 0,
                    usage = if (root.has("usage") && !root.isNull("usage")) root.optInt("usage") else 0,
                    nextReset = firstNonBlankString(root, "nextReset", "next_reset_at"),
                )
            } catch (_: Exception) {
                null
            }
        }

        private fun firstNonBlankString(json: JSONObject, vararg keys: String): String? {
            return keys.firstNotNullOfOrNull { key ->
                if (!json.has(key) || json.isNull(key)) {
                    null
                } else {
                    json.opt(key)
                        ?.toString()
                        ?.trim()
                        ?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
                }
            }
        }

        private fun optInt(json: JSONObject, vararg keys: String): Int? {
            return keys.firstNotNullOfOrNull { key ->
                if (json.has(key) && !json.isNull(key)) json.optInt(key) else null
            }
        }

        private fun optDouble(json: JSONObject, vararg keys: String): Double? {
            return keys.firstNotNullOfOrNull { key ->
                if (json.has(key) && !json.isNull(key)) json.optDouble(key) else null
            }
        }

        private fun optBoolean(json: JSONObject, vararg keys: String): Boolean? {
            return keys.firstNotNullOfOrNull { key ->
                if (json.has(key) && !json.isNull(key)) json.optBoolean(key) else null
            }
        }

        private fun parseTaskObject(task: JSONObject, fallbackTaskId: String): PortalTaskDetails? {
            val taskId = firstNonBlankString(task, "id")?.ifBlank { fallbackTaskId } ?: fallbackTaskId
            val status = firstNonBlankString(task, "status") ?: return null
            val prompt = firstNonBlankString(task, "task", "prompt").orEmpty()
            val promptPreview = firstNonBlankString(task, "promptPreview", "prompt_preview")
                ?.takeIf { it.isNotBlank() }
                ?: if (prompt.isNotBlank()) {
                    PortalTaskTracking.buildPromptPreview(prompt)
                } else {
                    ""
                }
            val createdAt = firstNonBlankString(task, "createdAt", "created_at")
            val finishedAt = firstNonBlankString(task, "finishedAt", "finished_at")
            return PortalTaskDetails(
                taskId = taskId,
                prompt = prompt,
                promptPreview = promptPreview,
                status = status,
                createdAt = createdAt,
                steps = optInt(task, "steps", "stepCount"),
                finishedAt = finishedAt,
                succeeded = optBoolean(task, "succeeded"),
                summary = extractTaskSummary(task),
                llmModel = firstNonBlankString(task, "llmModel", "model"),
                reasoning = optBoolean(task, "reasoning"),
                vision = optBoolean(task, "vision"),
                maxSteps = optInt(task, "maxSteps", "max_steps"),
                temperature = optDouble(task, "temperature"),
                executionTimeout = optInt(task, "executionTimeout", "execution_timeout", "timeout"),
            )
        }

        private fun parseTaskDetails(body: String, fallbackTaskId: String): PortalTaskDetails? {
            return try {
                val root = JSONObject(body)
                val task =
                    root.optJSONObject("task")
                        ?: root.takeIf { it.optString("status").isNotBlank() }
                        ?: return null
                parseTaskObject(task, fallbackTaskId)
            } catch (_: Exception) {
                null
            }
        }

        fun parseTaskHistoryPage(body: String): PortalTaskHistoryPage? {
            return try {
                val root = JSONObject(body)
                val itemsArray = root.optJSONArray("items") ?: return null
                val pagination = root.optJSONObject("pagination")
                val items = buildList {
                    for (index in 0 until itemsArray.length()) {
                        val taskObject = itemsArray.optJSONObject(index) ?: continue
                        val task = parseTaskObject(taskObject, "") ?: continue
                        add(
                            PortalTaskHistoryItem(
                                taskId = task.taskId,
                                prompt = task.prompt,
                                promptPreview = task.promptPreview,
                                status = task.status,
                                deviceId = firstNonBlankString(
                                    taskObject,
                                    "deviceId",
                                    "device_id",
                                ) ?: taskObject.optJSONObject("device")?.let { device ->
                                    firstNonBlankString(device, "id", "deviceId", "device_id")
                                },
                                createdAt = task.createdAt,
                                finishedAt = task.finishedAt,
                                steps = task.steps,
                                summary = task.summary,
                            ),
                        )
                    }
                }
                val page = pagination?.optInt("page") ?: 1
                val pageSize = pagination?.optInt("pageSize") ?: items.size
                val total = pagination?.optInt("total") ?: items.size
                val pages = pagination?.optInt("pages") ?: 1
                val hasNext = pagination?.optBoolean("hasNext") ?: false
                val hasPrev = pagination?.optBoolean("hasPrev") ?: false
                PortalTaskHistoryPage(
                    items = items,
                    page = page,
                    pageSize = pageSize,
                    total = total,
                    pages = pages,
                    hasNext = hasNext,
                    hasPrev = hasPrev,
                )
            } catch (_: Exception) {
                null
            }
        }

        fun parseTaskScreenshotSet(body: String): PortalTaskScreenshotSet? {
            return try {
                val root = JSONObject(body)
                val urls = root.optJSONArray("urls") ?: return PortalTaskScreenshotSet(emptyList())
                val parsedUrls = buildList {
                    for (index in 0 until urls.length()) {
                        val value = urls.optString(index).trim()
                        if (value.isNotBlank()) add(value)
                    }
                }
                PortalTaskScreenshotSet(parsedUrls)
            } catch (_: Exception) {
                null
            }
        }

        fun parseTaskTrajectory(body: String): PortalTaskTrajectorySet? {
            return try {
                val trimmedBody = body.trim()
                val trajectoryArray = when {
                    trimmedBody.startsWith("{") -> JSONObject(trimmedBody).optJSONArray("trajectory")
                    trimmedBody.startsWith("[") -> JSONArray(trimmedBody)
                    else -> null
                } ?: return null

                val parsedEvents = buildList {
                    for (index in 0 until trajectoryArray.length()) {
                        when (val item = trajectoryArray.opt(index)) {
                            is JSONObject -> {
                                val eventName = item.optString("event").trim().ifBlank { "UnknownEvent" }
                                val data = item.opt("data").takeUnless { it == JSONObject.NULL }
                                add(
                                    PortalTaskTrajectoryEvent(
                                        event = eventName,
                                        data = data,
                                        rawJson = item.toString(2),
                                    ),
                                )
                            }

                            else -> {
                                add(
                                    PortalTaskTrajectoryEvent(
                                        event = "UnknownEvent",
                                        data = item,
                                        rawJson = item?.toString().orEmpty(),
                                    ),
                                )
                            }
                        }
                    }
                }

                PortalTaskTrajectorySet(
                    PortalTaskTrajectoryUiSupport.filterVisibleEvents(parsedEvents),
                )
            } catch (_: Exception) {
                null
            }
        }

        private fun extractTaskSummary(task: JSONObject): String? {
            val output = task.opt("output")
            return when (output) {
                is String -> output.trim().takeIf { it.isNotBlank() }
                is JSONObject -> {
                    listOf(
                        "summary",
                        "message",
                        "detail",
                        "error",
                        "result",
                        "output"
                    ).firstNotNullOfOrNull { key ->
                        output.optString(key).trim().takeIf { it.isNotBlank() }
                    }
                        ?: output.toString()
                }

                else -> null
            }
        }
    }

    fun loadModels(
        restBaseUrl: String,
        authToken: String,
        callback: (PortalModelsLoadResult) -> Unit,
    ) {
        val request = buildModelsRequest(restBaseUrl, authToken)
        okHttpClient.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                callback(
                    PortalModelsLoadResult(
                        models = fallbackModelOptions(),
                        warningMessage = "Couldn't load models from Mobilerun. Using the documented fallback model list.",
                        loadedFromServer = false,
                    ),
                )
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use {
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        callback(
                            PortalModelsLoadResult(
                                models = fallbackModelOptions(),
                                warningMessage = "Couldn't load models from Mobilerun. Using the documented fallback model list.",
                                loadedFromServer = false,
                            ),
                        )
                        return
                    }

                    val normalizedIds = normalizeModelIds(body)
                    if (normalizedIds.isEmpty()) {
                        callback(
                            PortalModelsLoadResult(
                                models = fallbackModelOptions(),
                                warningMessage = "Couldn't load models from Mobilerun. Using the documented fallback model list.",
                                loadedFromServer = false,
                            ),
                        )
                        return
                    }

                    callback(
                        PortalModelsLoadResult(
                            models = buildModelOptions(normalizedIds),
                            loadedFromServer = true,
                        ),
                    )
                }
            }
        })
    }

    fun loadBalance(
        cloudBaseUrl: String,
        authToken: String,
        callback: (PortalBalanceResult) -> Unit,
    ) {
        val request = buildBalanceRequest(cloudBaseUrl, authToken)
        okHttpClient.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                callback(
                    PortalBalanceResult.Error(
                        "Could not reach Mobilerun billing right now. Check the connection and try again.",
                        retryable = true,
                    ),
                )
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use {
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        val parsedDetail = parseErrorDetail(body)
                        val result = when (response.code) {
                            401, 403 -> PortalBalanceResult.Error(
                                "Mobilerun rejected the saved API key. Sign in again or update the key.",
                            )

                            404 -> PortalBalanceResult.Unavailable(
                                parsedDetail ?: "Credits balance is unavailable for this connection.",
                            )

                            in 500..599 -> PortalBalanceResult.Error(
                                "Mobilerun could not load credits right now. Try again in a moment.",
                                retryable = true,
                            )

                            else -> PortalBalanceResult.Error(
                                parsedDetail ?: "Mobilerun returned an unexpected response.",
                            )
                        }
                        callback(result)
                        return
                    }

                    val info = parseBalanceInfo(body)
                    if (info == null) {
                        callback(
                            PortalBalanceResult.Error(
                                "Mobilerun returned an unexpected response.",
                            ),
                        )
                        return
                    }

                    callback(PortalBalanceResult.Success(info))
                }
            }
        })
    }

    fun launchTask(
        restBaseUrl: String,
        authToken: String,
        deviceId: String,
        draft: PortalTaskDraft,
        launchStartedAtMs: Long = System.currentTimeMillis(),
        callback: (PortalTaskLaunchResult) -> Unit,
    ) {
        val completionGate = AtomicBoolean(false)
        val request = buildLaunchTaskRequest(restBaseUrl, authToken, deviceId, draft)
        okHttpClient.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                Log.w(TAG, "Task launch request failed before a usable response. Starting recovery.", e)
                recoverLaunchTask(
                    restBaseUrl = restBaseUrl,
                    authToken = authToken,
                    deviceId = deviceId,
                    draft = draft,
                    launchStartedAtMs = launchStartedAtMs,
                    fallbackMessage = "Could not reach Mobilerun. Check the connection and try again.",
                    completionGate = completionGate,
                    callback = callback,
                )
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use {
                    val body = response.body?.string().orEmpty()
                    val parsedTaskId = parseLaunchTaskId(body)
                    Log.d(
                        TAG,
                        "Launch response code=${response.code} success=${response.isSuccessful} parsedTaskId=${parsedTaskId ?: "<none>"}",
                    )
                    if (!response.isSuccessful) {
                        val parsedDetail = parseErrorDetail(body)
                        val message = when (response.code) {
                            401, 403 -> "Mobilerun rejected the saved API key. Sign in again or update the key."
                            404 -> "This connected device was not found in Mobilerun. Reconnect it and try again."
                            400, 412, 422 -> parsedDetail
                                ?: "Mobilerun rejected the task request. Check the selected model and settings."

                            in 500..599 -> "Mobilerun could not start the task right now. Try again in a moment."
                            else -> parsedDetail ?: "Mobilerun returned an unexpected response."
                        }
                        if (response.code !in HARD_LAUNCH_FAILURE_CODES &&
                            !parsedTaskId.isNullOrBlank()
                        ) {
                            completeLaunchOnce(
                                completionGate,
                                callback,
                                PortalTaskLaunchResult.Success(
                                    PortalTaskLaunchSuccess(taskId = parsedTaskId),
                                ),
                            )
                            return
                        }
                        if (response.code !in HARD_LAUNCH_FAILURE_CODES) {
                            recoverLaunchTask(
                                restBaseUrl = restBaseUrl,
                                authToken = authToken,
                                deviceId = deviceId,
                                draft = draft,
                                launchStartedAtMs = launchStartedAtMs,
                                fallbackMessage = message,
                                completionGate = completionGate,
                                callback = callback,
                            )
                            return
                        }
                        completeLaunchOnce(
                            completionGate,
                            callback,
                            PortalTaskLaunchResult.Error(message),
                        )
                        return
                    }

                    if (!parsedTaskId.isNullOrBlank()) {
                        completeLaunchOnce(
                            completionGate,
                            callback,
                            PortalTaskLaunchResult.Success(
                                PortalTaskLaunchSuccess(taskId = parsedTaskId),
                            ),
                        )
                        return
                    }

                    recoverLaunchTask(
                        restBaseUrl = restBaseUrl,
                        authToken = authToken,
                        deviceId = deviceId,
                        draft = draft,
                        launchStartedAtMs = launchStartedAtMs,
                        fallbackMessage = "Mobilerun returned an unexpected response.",
                        completionGate = completionGate,
                        callback = callback,
                    )
                }
            }
        })
    }

    private fun recoverLaunchTask(
        restBaseUrl: String,
        authToken: String,
        deviceId: String,
        draft: PortalTaskDraft,
        launchStartedAtMs: Long,
        fallbackMessage: String,
        completionGate: AtomicBoolean,
        callback: (PortalTaskLaunchResult) -> Unit,
    ) {
        val request = buildListTasksRequest(
            restBaseUrl = restBaseUrl,
            authToken = authToken,
            query = null,
            page = 1,
            pageSize = 20,
        )
        okHttpClient.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                Log.w(TAG, "Launch recovery list request failed. Retrying if time remains.", e)
                scheduleLaunchRecoveryRetry(
                    restBaseUrl = restBaseUrl,
                    authToken = authToken,
                    deviceId = deviceId,
                    draft = draft,
                    launchStartedAtMs = launchStartedAtMs,
                    fallbackMessage = fallbackMessage,
                    completionGate = completionGate,
                    callback = callback,
                )
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use {
                    if (!response.isSuccessful) {
                        Log.w(TAG, "Launch recovery list request returned ${response.code}. Retrying if time remains.")
                        scheduleLaunchRecoveryRetry(
                            restBaseUrl = restBaseUrl,
                            authToken = authToken,
                            deviceId = deviceId,
                            draft = draft,
                            launchStartedAtMs = launchStartedAtMs,
                            fallbackMessage = fallbackMessage,
                            completionGate = completionGate,
                            callback = callback,
                        )
                        return
                    }

                    val page = parseTaskHistoryPage(response.body?.string().orEmpty())
                    val recoveredTaskId = findRecoverableTaskId(
                        page = page,
                        deviceId = deviceId,
                        prompt = draft.prompt,
                        launchStartedAtMs = launchStartedAtMs,
                    )
                    if (recoveredTaskId.isNullOrBlank()) {
                        Log.d(TAG, "Launch recovery did not find a matching task yet. Retrying if time remains.")
                        scheduleLaunchRecoveryRetry(
                            restBaseUrl = restBaseUrl,
                            authToken = authToken,
                            deviceId = deviceId,
                            draft = draft,
                            launchStartedAtMs = launchStartedAtMs,
                            fallbackMessage = fallbackMessage,
                            completionGate = completionGate,
                            callback = callback,
                        )
                        return
                    }

                    Log.i(TAG, "Recovered launched task via recent task history: $recoveredTaskId")
                    completeLaunchOnce(
                        completionGate,
                        callback,
                        PortalTaskLaunchResult.Success(
                            PortalTaskLaunchSuccess(taskId = recoveredTaskId),
                        ),
                    )
                }
            }
        })
    }

    private fun scheduleLaunchRecoveryRetry(
        restBaseUrl: String,
        authToken: String,
        deviceId: String,
        draft: PortalTaskDraft,
        launchStartedAtMs: Long,
        fallbackMessage: String,
        completionGate: AtomicBoolean,
        callback: (PortalTaskLaunchResult) -> Unit,
    ) {
        if (completionGate.get()) return

        val nowMs = System.currentTimeMillis()
        if (!hasLaunchRecoveryTimeRemaining(launchStartedAtMs, nowMs)) {
            Log.w(TAG, "Launch recovery window exhausted. Surfacing launch failure.")
            completeLaunchOnce(
                completionGate,
                callback,
                PortalTaskLaunchResult.Error(fallbackMessage),
            )
            return
        }

        val delayMs = nextLaunchRecoveryDelayMs(launchStartedAtMs, nowMs)
        Log.d(TAG, "Scheduling launch recovery retry in ${delayMs}ms")
        LAUNCH_RECOVERY_EXECUTOR.schedule(
            {
                recoverLaunchTask(
                    restBaseUrl = restBaseUrl,
                    authToken = authToken,
                    deviceId = deviceId,
                    draft = draft,
                    launchStartedAtMs = launchStartedAtMs,
                    fallbackMessage = fallbackMessage,
                    completionGate = completionGate,
                    callback = callback,
                )
            },
            delayMs,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun completeLaunchOnce(
        completionGate: AtomicBoolean,
        callback: (PortalTaskLaunchResult) -> Unit,
        result: PortalTaskLaunchResult,
    ) {
        if (completionGate.compareAndSet(false, true)) {
            callback(result)
        }
    }

    fun getTaskStatus(
        restBaseUrl: String,
        authToken: String,
        taskId: String,
        callback: (PortalTaskStatusResult) -> Unit,
    ) {
        val request = buildTaskStatusRequest(restBaseUrl, authToken, taskId)
        okHttpClient.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                callback(PortalTaskStatusResult.Error("Could not reach Mobilerun. Check the connection and try again."))
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use {
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        val parsedDetail = parseErrorDetail(body)
                        val message = when (response.code) {
                            401, 403 -> "Mobilerun rejected the saved API key. Sign in again or update the key."
                            404 -> "This task was not found in Mobilerun anymore."
                            in 500..599 -> "Mobilerun could not load the task status right now. Try again in a moment."
                            else -> parsedDetail ?: "Mobilerun returned an unexpected response."
                        }
                        callback(PortalTaskStatusResult.Error(message))
                        return
                    }

                    val status = parseTaskStatus(body)
                    if (status == null) {
                        callback(PortalTaskStatusResult.Error("Mobilerun returned an unexpected response."))
                        return
                    }

                    callback(PortalTaskStatusResult.Success(PortalTaskStatusSuccess(status)))
                }
            }
        })
    }

    fun getTask(
        restBaseUrl: String,
        authToken: String,
        taskId: String,
        callback: (PortalTaskDetailsResult) -> Unit,
    ) {
        val request = buildTaskDetailsRequest(restBaseUrl, authToken, taskId)
        okHttpClient.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                callback(PortalTaskDetailsResult.Error("Could not reach Mobilerun. Check the connection and try again."))
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use {
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        val parsedDetail = parseErrorDetail(body)
                        val message = when (response.code) {
                            401, 403 -> "Mobilerun rejected the saved API key. Sign in again or update the key."
                            404 -> "This task was not found in Mobilerun anymore."
                            in 500..599 -> "Mobilerun could not load the task details right now. Try again in a moment."
                            else -> parsedDetail ?: "Mobilerun returned an unexpected response."
                        }
                        callback(PortalTaskDetailsResult.Error(message))
                        return
                    }

                    val task = parseTaskDetails(body, taskId)
                    if (task == null) {
                        callback(PortalTaskDetailsResult.Error("Mobilerun returned an unexpected response."))
                        return
                    }

                    callback(PortalTaskDetailsResult.Success(task))
                }
            }
        })
    }

    fun listTasks(
        restBaseUrl: String,
        authToken: String,
        query: String?,
        page: Int,
        pageSize: Int,
        callback: (PortalTaskHistoryResult) -> Unit,
    ) {
        val request = buildListTasksRequest(restBaseUrl, authToken, query, page, pageSize)
        okHttpClient.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                callback(PortalTaskHistoryResult.Error("Could not reach Mobilerun. Check the connection and try again."))
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use {
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        val parsedDetail = parseErrorDetail(body)
                        val message = when (response.code) {
                            401, 403 -> "Mobilerun rejected the saved API key. Sign in again or update the key."
                            in 500..599 -> "Mobilerun could not load the task history right now. Try again in a moment."
                            else -> parsedDetail ?: "Mobilerun returned an unexpected response."
                        }
                        callback(PortalTaskHistoryResult.Error(message))
                        return
                    }

                    val pageResult = parseTaskHistoryPage(body)
                    if (pageResult == null) {
                        callback(PortalTaskHistoryResult.Error("Mobilerun returned an unexpected response."))
                        return
                    }

                    callback(PortalTaskHistoryResult.Success(pageResult))
                }
            }
        })
    }

    fun getTaskScreenshots(
        restBaseUrl: String,
        authToken: String,
        taskId: String,
        callback: (PortalTaskScreenshotResult) -> Unit,
    ) {
        val request = buildTaskScreenshotsRequest(restBaseUrl, authToken, taskId)
        okHttpClient.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                callback(PortalTaskScreenshotResult.Error("Could not reach Mobilerun. Check the connection and try again."))
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use {
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        val parsedDetail = parseErrorDetail(body)
                        val message = when (response.code) {
                            401, 403 -> "Mobilerun rejected the saved API key. Sign in again or update the key."
                            404 -> "This task screenshot list was not found in Mobilerun anymore."
                            in 500..599 -> "Mobilerun could not load screenshots right now. Try again in a moment."
                            else -> parsedDetail ?: "Mobilerun returned an unexpected response."
                        }
                        callback(PortalTaskScreenshotResult.Error(message))
                        return
                    }

                    val screenshots = parseTaskScreenshotSet(body)
                    if (screenshots == null) {
                        callback(PortalTaskScreenshotResult.Error("Mobilerun returned an unexpected response."))
                        return
                    }

                    callback(PortalTaskScreenshotResult.Success(screenshots))
                }
            }
        })
    }

    fun getTaskTrajectory(
        restBaseUrl: String,
        authToken: String,
        taskId: String,
        callback: (PortalTaskTrajectoryResult) -> Unit,
    ) {
        val request = buildTaskTrajectoryRequest(restBaseUrl, authToken, taskId)
        okHttpClient.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                callback(PortalTaskTrajectoryResult.Error("Could not reach Mobilerun. Check the connection and try again."))
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use {
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        val parsedDetail = parseErrorDetail(body)
                        val message = when (response.code) {
                            401, 403 -> "Mobilerun rejected the saved API key. Sign in again or update the key."
                            404 -> "This task trajectory was not found in Mobilerun anymore."
                            in 500..599 -> "Mobilerun could not load trajectory right now. Try again in a moment."
                            else -> parsedDetail ?: "Mobilerun returned an unexpected response."
                        }
                        callback(PortalTaskTrajectoryResult.Error(message))
                        return
                    }

                    val trajectory = parseTaskTrajectory(body)
                    if (trajectory == null) {
                        callback(PortalTaskTrajectoryResult.Error("Mobilerun returned an unexpected response."))
                        return
                    }

                    callback(PortalTaskTrajectoryResult.Success(trajectory))
                }
            }
        })
    }

    fun cancelTask(
        restBaseUrl: String,
        authToken: String,
        taskId: String,
        callback: (PortalTaskCancelResult) -> Unit,
    ) {
        val request = buildCancelTaskRequest(restBaseUrl, authToken, taskId)
        okHttpClient.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                callback(PortalTaskCancelResult.Error("Could not reach Mobilerun. Check the connection and try again."))
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use {
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        val parsedDetail = parseErrorDetail(body)
                        if (response.code == 400 &&
                            parsedDetail?.contains("already finished", ignoreCase = true) == true
                        ) {
                            callback(PortalTaskCancelResult.AlreadyFinished)
                            return
                        }

                        val message = when (response.code) {
                            401, 403 -> "Mobilerun rejected the saved API key. Sign in again or update the key."
                            404 -> "This task was not found in Mobilerun anymore."
                            in 500..599 -> "Mobilerun could not cancel the task right now. Try again in a moment."
                            else -> parsedDetail ?: "Mobilerun returned an unexpected response."
                        }
                        callback(PortalTaskCancelResult.Error(message))
                        return
                    }

                    callback(PortalTaskCancelResult.Success)
                }
            }
        })
    }
}
