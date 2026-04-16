package com.droidrun.portal.triggers

import android.content.Context
import com.droidrun.portal.taskprompt.PortalActiveTaskRecord
import com.droidrun.portal.taskprompt.PortalTaskLaunchCoordinator
import com.droidrun.portal.taskprompt.PortalTaskLaunchMetadata
import com.droidrun.portal.taskprompt.PortalTaskSettings

class TriggerTaskLauncher(
    context: Context,
    private val taskLaunchCoordinator: PortalTaskLaunchCoordinator = PortalTaskLaunchCoordinator(context),
) {
    sealed class Result {
        data class Success(val record: PortalActiveTaskRecord) : Result()
        data class Error(val message: String) : Result()
        object Busy : Result()
    }

    fun launchPrompt(
        prompt: String,
        settings: PortalTaskSettings,
        triggerRuleId: String?,
        returnToPortalOnTerminal: Boolean,
        skipBusyCheck: Boolean = false,
        memoryNamespace: String? = null,
        onComplete: (Result) -> Unit,
    ) {
        taskLaunchCoordinator.launchPrompt(
            prompt = prompt,
            settings = settings,
            broadcastTaskStateChanged = true,
            metadata = PortalTaskLaunchMetadata(
                triggerRuleId = triggerRuleId,
                returnToPortalOnTerminal = returnToPortalOnTerminal,
            ),
            skipBusyCheck = skipBusyCheck,
            memoryNamespace = memoryNamespace,
        ) { result ->
            when (result) {
                is PortalTaskLaunchCoordinator.Result.Success -> onComplete(Result.Success(result.record))
                PortalTaskLaunchCoordinator.Result.Busy -> onComplete(Result.Busy)
                is PortalTaskLaunchCoordinator.Result.Error -> onComplete(Result.Error(result.message))
            }
        }
    }
}
