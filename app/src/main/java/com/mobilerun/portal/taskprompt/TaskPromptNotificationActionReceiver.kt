package com.mobilerun.portal.taskprompt

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.mobilerun.portal.config.ConfigManager

class TaskPromptNotificationActionReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "TaskPromptNotifAction"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != TaskPromptNotificationManager.ACTION_CANCEL_TASK) {
            return
        }

        Log.d(TAG, "Received task notification action=${intent.action} data=${intent.data}")
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        PortalTaskStateMonitor.initialize(appContext)
        val configManager = ConfigManager.getInstance(appContext)
        val activeTask = configManager.activePortalTask
        val taskId = intent.getStringExtra(TaskPromptNotificationManager.EXTRA_TASK_ID)
            ?.takeIf { it.isNotBlank() }
            ?: activeTask?.taskId

        if (activeTask == null || taskId.isNullOrBlank()) {
            Log.w(TAG, "No active task found for cancel action. Clearing notification.")
            TaskPromptNotificationManager.cancel(appContext)
            TaskPromptNotificationManager.broadcastTaskStateChanged(appContext)
            pendingResult.finish()
            return
        }
        if (activeTask.taskId != taskId) {
            Log.w(TAG, "Ignoring stale cancel action for taskId=$taskId while activeTask=${activeTask.taskId}")
            pendingResult.finish()
            return
        }

        val authToken = configManager.reverseConnectionToken.trim()
        val restBaseUrl = PortalCloudClient.deriveRestBaseUrl(configManager.reverseConnectionUrlOrDefault)
        if (authToken.isBlank() || restBaseUrl == null) {
            Log.w(TAG, "Cannot cancel task $taskId because task API is unavailable.")
            pendingResult.finish()
            return
        }

        val previousRecord = activeTask
        val cancellingRecord =
            PortalTaskTracking.withUpdatedStatus(activeTask, PortalTaskTracking.STATUS_CANCELLING)
        Log.i(TAG, "Starting notification cancel flow for taskId=$taskId")
        configManager.saveActivePortalTask(cancellingRecord)
        TaskPromptNotificationManager.showActiveTask(appContext, cancellingRecord)
        TaskPromptNotificationManager.broadcastTaskStateChanged(appContext)
        PortalTaskStateMonitor.reconcileActiveTask(immediate = true)

        val client = PortalCloudClient()
        client.cancelTask(restBaseUrl, authToken, taskId) { result ->
            when (result) {
                PortalTaskCancelResult.Success -> {
                    Log.i(TAG, "Cancel request accepted for taskId=$taskId")
                    PortalTaskStateMonitor.reconcileActiveTask(immediate = true)
                    TaskPromptNotificationManager.broadcastTaskStateChanged(appContext)
                    pendingResult.finish()
                }

                PortalTaskCancelResult.AlreadyFinished -> {
                    Log.i(TAG, "Cancel action reached already-finished taskId=$taskId. Refreshing status.")
                    client.getTaskStatus(restBaseUrl, authToken, taskId) { statusResult ->
                        when (statusResult) {
                            is PortalTaskStatusResult.Success -> {
                                val updatedRecord = PortalTaskTracking.withUpdatedStatus(
                                    cancellingRecord,
                                    statusResult.value.status,
                                )
                                Log.i(TAG, "Recovered finished status=${statusResult.value.status} for taskId=$taskId")
                                configManager.saveActivePortalTask(updatedRecord)
                                PortalTaskStateMonitor.reconcileActiveTask(immediate = true)
                            }

                            is PortalTaskStatusResult.Error -> {
                                Log.w(TAG, "Could not refresh final status for taskId=$taskId. Restoring previous state.")
                                configManager.saveActivePortalTask(previousRecord)
                                TaskPromptNotificationManager.showActiveTask(appContext, previousRecord)
                                PortalTaskStateMonitor.reconcileActiveTask(immediate = true)
                            }
                        }
                        TaskPromptNotificationManager.broadcastTaskStateChanged(appContext)
                        pendingResult.finish()
                    }
                }

                is PortalTaskCancelResult.Error -> {
                    Log.w(TAG, "Cancel request failed for taskId=$taskId: ${result.message}")
                    configManager.saveActivePortalTask(previousRecord)
                    TaskPromptNotificationManager.showActiveTask(appContext, previousRecord)
                    TaskPromptNotificationManager.broadcastTaskStateChanged(appContext)
                    PortalTaskStateMonitor.reconcileActiveTask(immediate = true)
                    pendingResult.finish()
                }
            }
        }
    }
}
