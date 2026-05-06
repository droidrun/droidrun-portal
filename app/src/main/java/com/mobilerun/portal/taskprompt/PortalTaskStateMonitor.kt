package com.mobilerun.portal.taskprompt

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.mobilerun.portal.R
import com.mobilerun.portal.config.ConfigManager
import com.mobilerun.portal.service.MobilerunAccessibilityService
import com.mobilerun.portal.ui.MainActivity

object PortalTaskStateMonitor {
    private const val TAG = "PortalTaskMonitor"
    private const val POLL_INTERVAL_MS = 2000L

    private lateinit var appContext: Context
    private val mainHandler: Handler by lazy(LazyThreadSafetyMode.NONE) {
        Handler(Looper.getMainLooper())
    }
    private val portalCloudClient = PortalCloudClient()

    @Volatile
    private var statusRequestInFlight = false
    private var pollRunnable: Runnable? = null

    fun initialize(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
    }

    fun reconcileActiveTask(immediate: Boolean = false) {
        if (!::appContext.isInitialized) return
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { reconcileActiveTask(immediate) }
            return
        }

        val configManager = ConfigManager.getInstance(appContext)
        val activeTask = configManager.activePortalTask
        if (activeTask == null) {
            Log.d(TAG, "No active task to reconcile. Clearing task notification state.")
            stopScheduledPoll()
            TaskPromptNotificationManager.cancel(appContext)
            return
        }

        if (PortalTaskTracking.isBlockingStatus(activeTask.lastStatus)) {
            if (PortalTaskTracking.hasReachedPollingDeadline(activeTask, System.currentTimeMillis())) {
                handleTerminalState(
                    if (activeTask.lastStatus == PortalTaskTracking.STATUS_TRACKING_TIMEOUT) {
                        activeTask
                    } else {
                        activeTask.copy(lastStatus = PortalTaskTracking.STATUS_TRACKING_TIMEOUT)
                    },
                )
                return
            }

            val startedToastRecord = maybeShowStartedToast(activeTask)
            TaskPromptNotificationManager.showActiveTask(appContext, startedToastRecord)
            schedulePoll(if (immediate) 0L else POLL_INTERVAL_MS)
            return
        }

        stopScheduledPoll()
        if (PortalTaskTracking.isLocalTerminalStatus(activeTask.lastStatus)) {
            if (PortalTaskTracking.shouldHandleTerminalTransition(activeTask)) {
                handleTerminalState(activeTask)
            } else {
                Log.d(
                    TAG,
                    "Terminal state already handled for taskId=${activeTask.taskId} status=${activeTask.lastStatus}",
                )
            }
        } else {
            TaskPromptNotificationManager.cancel(appContext)
        }
    }

    private fun schedulePoll(delayMs: Long) {
        if (statusRequestInFlight) return
        pollRunnable?.let(mainHandler::removeCallbacks)
        pollRunnable = Runnable {
            pollRunnable = null
            pollActiveTaskStatus()
        }
        mainHandler.postDelayed(pollRunnable!!, delayMs.coerceAtLeast(0L))
    }

    private fun stopScheduledPoll() {
        pollRunnable?.let(mainHandler::removeCallbacks)
        pollRunnable = null
    }

    private fun pollActiveTaskStatus() {
        if (!::appContext.isInitialized) return

        val configManager = ConfigManager.getInstance(appContext)
        val activeTask = configManager.activePortalTask ?: run {
            stopScheduledPoll()
            return
        }
        if (!PortalTaskTracking.isBlockingStatus(activeTask.lastStatus)) {
            reconcileActiveTask()
            return
        }
        if (PortalTaskTracking.hasReachedPollingDeadline(activeTask, System.currentTimeMillis())) {
            handleTerminalState(activeTask.copy(lastStatus = PortalTaskTracking.STATUS_TRACKING_TIMEOUT))
            return
        }

        val authToken = configManager.reverseConnectionToken.trim()
        val restBaseUrl =
            PortalCloudClient.deriveRestBaseUrl(configManager.reverseConnectionUrlOrDefault)
        if (authToken.isBlank() || restBaseUrl == null) {
            stopScheduledPoll()
            return
        }

        statusRequestInFlight = true
        portalCloudClient.getTaskStatus(restBaseUrl, authToken, activeTask.taskId) { result ->
            mainHandler.post {
                statusRequestInFlight = false
                when (result) {
                    is PortalTaskStatusResult.Success -> handleStatusSuccess(activeTask, result.value.status)
                    is PortalTaskStatusResult.Error -> schedulePoll(POLL_INTERVAL_MS)
                }
            }
        }
    }

    private fun handleStatusSuccess(previousRecord: PortalActiveTaskRecord, status: String) {
        val configManager = ConfigManager.getInstance(appContext)
        val currentRecord = configManager.activePortalTask ?: previousRecord
        if (currentRecord.taskId != previousRecord.taskId) {
            reconcileActiveTask(immediate = true)
            return
        }

        val updatedRecord = PortalTaskTracking.withUpdatedStatus(currentRecord, status)
        var finalRecord = updatedRecord
        if (finalRecord != currentRecord) {
            configManager.saveActivePortalTask(finalRecord)
        }

        if (PortalTaskTracking.isTerminalStatus(finalRecord.lastStatus)) {
            handleTerminalState(finalRecord)
            return
        }

        finalRecord = maybeShowStartedToast(finalRecord)
        TaskPromptNotificationManager.showActiveTask(appContext, finalRecord)
        TaskPromptNotificationManager.broadcastTaskStateChanged(appContext)
        schedulePoll(POLL_INTERVAL_MS)
    }

    private fun handleTerminalState(record: PortalActiveTaskRecord) {
        stopScheduledPoll()
        val configManager = ConfigManager.getInstance(appContext)
        val currentRecord = configManager.activePortalTask ?: record
        if (currentRecord.taskId != record.taskId) {
            reconcileActiveTask(immediate = true)
            return
        }

        var finalRecord = PortalTaskTracking.withUpdatedStatus(currentRecord, record.lastStatus)
        if (finalRecord.terminalTransitionHandled) {
            if (finalRecord != currentRecord) {
                configManager.saveActivePortalTask(finalRecord)
            }
            Log.d(
                TAG,
                "Skipping duplicate terminal handling for taskId=${finalRecord.taskId} status=${finalRecord.lastStatus}",
            )
            return
        }

        val shouldShowToast = !finalRecord.terminalToastShown
        finalRecord = finalRecord.copy(
            terminalToastShown = true,
            terminalTransitionHandled = true,
        )
        configManager.saveActivePortalTask(finalRecord)

        if (shouldShowToast) {
            showTerminalToast(finalRecord)
        }

        val returnedRecord = maybeReturnToPortal(finalRecord)
        if (returnedRecord != finalRecord) {
            configManager.saveActivePortalTask(returnedRecord)
            finalRecord = returnedRecord
        }

        TaskPromptNotificationManager.showTerminalTask(
            context = appContext,
            record = finalRecord,
            details = null,
            fallbackMessage = buildTerminalFallbackMessage(finalRecord),
        )
        TaskPromptNotificationManager.broadcastTaskStateChanged(appContext)
    }

    private fun maybeShowStartedToast(record: PortalActiveTaskRecord): PortalActiveTaskRecord {
        if (!PortalTaskTracking.shouldShowStartedToast(record)) {
            return record
        }

        Log.i(TAG, "Showing task started toast for taskId=${record.taskId}")
        Toast.makeText(
            appContext,
            appContext.getString(R.string.task_prompt_started),
            Toast.LENGTH_SHORT,
        ).show()
        val updatedRecord = record.copy(startedToastShown = true)
        ConfigManager.getInstance(appContext).saveActivePortalTask(updatedRecord)
        return updatedRecord
    }

    private fun showTerminalToast(record: PortalActiveTaskRecord) {
        val messageRes = when (record.lastStatus) {
            PortalTaskTracking.STATUS_COMPLETED -> R.string.task_prompt_completed_toast
            PortalTaskTracking.STATUS_FAILED -> R.string.task_prompt_failed_toast
            PortalTaskTracking.STATUS_CANCELLED -> R.string.task_prompt_cancelled_toast
            PortalTaskTracking.STATUS_TRACKING_TIMEOUT -> R.string.task_prompt_timeout_toast
            else -> null
        } ?: return

        Toast.makeText(appContext, appContext.getString(messageRes), Toast.LENGTH_SHORT).show()
    }

    private fun maybeReturnToPortal(record: PortalActiveTaskRecord): PortalActiveTaskRecord {
        if (!shouldReturnToPortal(record)) {
            return record
        }

        return if (launchPortalToForeground()) {
            Log.i(TAG, "Returned to Portal for taskId=${record.taskId}")
            record.copy(terminalReturnHandled = true)
        } else {
            Log.w(TAG, "Failed to return to Portal for taskId=${record.taskId}")
            record
        }
    }

    private fun launchPortalToForeground(): Boolean {
        val intent = Intent(appContext, MainActivity::class.java).apply {
            flags =
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
        }

        val accessibilityService = MobilerunAccessibilityService.getInstance()
        if (accessibilityService != null) {
            try {
                accessibilityService.startActivity(intent)
                Log.d(TAG, "Returned to Portal through accessibility service.")
                return true
            } catch (error: Exception) {
                Log.w(TAG, "Accessibility service could not return to Portal.", error)
            }
        }

        return try {
            appContext.startActivity(intent)
            Log.d(TAG, "Returned to Portal through app context fallback.")
            true
        } catch (error: Exception) {
            Log.w(TAG, "App context fallback could not return to Portal.", error)
            false
        }
    }

    private fun buildTerminalFallbackMessage(record: PortalActiveTaskRecord): String {
        return when (record.lastStatus) {
            PortalTaskTracking.STATUS_COMPLETED -> appContext.getString(R.string.task_prompt_completed_generic)
            PortalTaskTracking.STATUS_FAILED -> appContext.getString(R.string.task_prompt_failed_generic)
            PortalTaskTracking.STATUS_CANCELLED -> appContext.getString(R.string.task_prompt_cancelled_generic)
            else -> appContext.getString(R.string.task_prompt_timeout_stopped)
        }
    }

    internal fun shouldReturnToPortal(record: PortalActiveTaskRecord): Boolean {
        return record.returnToPortalOnTerminal &&
            !record.terminalReturnHandled &&
            (record.lastStatus == PortalTaskTracking.STATUS_COMPLETED ||
                record.lastStatus == PortalTaskTracking.STATUS_FAILED ||
                record.lastStatus == PortalTaskTracking.STATUS_TRACKING_TIMEOUT)
    }
}
