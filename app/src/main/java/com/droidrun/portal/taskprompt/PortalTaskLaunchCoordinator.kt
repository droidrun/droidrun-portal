package com.droidrun.portal.taskprompt

import android.content.Context
import com.droidrun.portal.R
import com.droidrun.portal.config.ConfigManager

class PortalTaskLaunchCoordinator(
    context: Context,
    private val portalCloudClient: PortalCloudClient = PortalCloudClient(),
) {
    sealed class Result {
        data class Success(val record: PortalActiveTaskRecord) : Result()
        data class Error(val message: String) : Result()
        object Busy : Result()
    }

    private val appContext = context.applicationContext
    private val configManager = ConfigManager.getInstance(appContext)

    fun launchPrompt(
        prompt: String,
        settings: PortalTaskSettings,
        broadcastTaskStateChanged: Boolean,
        metadata: PortalTaskLaunchMetadata = PortalTaskLaunchMetadata(),
        skipBusyCheck: Boolean = false,
        memoryNamespace: String? = null,
        onComplete: (Result) -> Unit,
    ) {
        val authToken = configManager.reverseConnectionToken.trim()
        val restBaseUrl = PortalCloudClient.deriveRestBaseUrl(configManager.reverseConnectionUrlOrDefault)
        val activeTask = configManager.activePortalTask

        if (authToken.isBlank()) {
            onComplete(Result.Error(appContext.getString(R.string.task_prompt_missing_api_key)))
            return
        }

        if (restBaseUrl == null) {
            onComplete(Result.Error(appContext.getString(R.string.task_prompt_unsupported_custom_url)))
            return
        }

        if (!skipBusyCheck && activeTask != null && PortalTaskTracking.isBlockingStatus(activeTask.lastStatus)) {
            onComplete(Result.Busy)
            return
        }

        val draft = PortalTaskDraft(
            prompt = prompt,
            settings = settings,
            returnToPortalOnTerminal = metadata.returnToPortalOnTerminal,
            memoryNamespace = memoryNamespace,
        )
        portalCloudClient.launchTask(
            restBaseUrl = restBaseUrl,
            authToken = authToken,
            deviceId = configManager.deviceID,
            draft = draft,
        ) { result ->
            when (result) {
                is PortalTaskLaunchResult.Success -> {
                    val record = buildActiveTaskRecord(
                        prompt = prompt,
                        settings = settings,
                        taskId = result.value.taskId,
                        metadata = metadata,
                    )
                    val existingTask = configManager.activePortalTask
                    if (existingTask == null || !PortalTaskTracking.isBlockingStatus(existingTask.lastStatus)) {
                        configManager.saveActivePortalTask(record)
                        TaskPromptNotificationManager.showActiveTask(appContext, record)
                        PortalTaskStateMonitor.initialize(appContext)
                        PortalTaskStateMonitor.reconcileActiveTask(immediate = true)
                    }
                    if (broadcastTaskStateChanged) {
                        TaskPromptNotificationManager.broadcastTaskStateChanged(appContext)
                    }
                    onComplete(Result.Success(record))
                }

                is PortalTaskLaunchResult.Error -> onComplete(Result.Error(result.message))
            }
        }
    }

    companion object {
        fun buildActiveTaskRecord(
            prompt: String,
            settings: PortalTaskSettings,
            taskId: String,
            startedAtMs: Long = System.currentTimeMillis(),
            metadata: PortalTaskLaunchMetadata = PortalTaskLaunchMetadata(),
        ): PortalActiveTaskRecord {
            return PortalActiveTaskRecord(
                taskId = taskId,
                promptPreview = PortalTaskTracking.buildPromptPreview(prompt),
                startedAtMs = startedAtMs,
                executionTimeoutSec = settings.executionTimeout,
                pollDeadlineMs = PortalTaskTracking.computePollDeadline(
                    startedAtMs = startedAtMs,
                    executionTimeoutSec = settings.executionTimeout,
                ),
                lastStatus = PortalTaskTracking.STATUS_CREATED,
                startedToastShown = false,
                terminalToastShown = false,
                triggerRuleId = metadata.triggerRuleId,
                returnToPortalOnTerminal = metadata.returnToPortalOnTerminal,
                terminalReturnHandled = false,
                terminalTransitionHandled = false,
            )
        }
    }
}
