package com.mobilerun.portal.taskprompt

data class PortalActiveTaskRecord(
    val taskId: String,
    val promptPreview: String,
    val startedAtMs: Long,
    val executionTimeoutSec: Int,
    val pollDeadlineMs: Long,
    val lastStatus: String = PortalTaskTracking.STATUS_CREATED,
    val startedToastShown: Boolean = false,
    val terminalToastShown: Boolean = false,
    val triggerRuleId: String? = null,
    val returnToPortalOnTerminal: Boolean = false,
    val terminalReturnHandled: Boolean = false,
    val terminalTransitionHandled: Boolean = false,
)

data class PortalTaskLaunchMetadata(
    val triggerRuleId: String? = null,
    val returnToPortalOnTerminal: Boolean = false,
)

data class PortalTaskHistoryItem(
    val taskId: String,
    val prompt: String,
    val promptPreview: String,
    val status: String,
    val deviceId: String? = null,
    val createdAt: String? = null,
    val finishedAt: String? = null,
    val steps: Int? = null,
    val summary: String? = null,
)

data class PortalTaskHistoryPage(
    val items: List<PortalTaskHistoryItem>,
    val page: Int,
    val pageSize: Int,
    val total: Int,
    val pages: Int,
    val hasNext: Boolean,
    val hasPrev: Boolean,
)

data class PortalTaskDetails(
    val taskId: String,
    val prompt: String,
    val promptPreview: String,
    val status: String,
    val createdAt: String? = null,
    val steps: Int? = null,
    val finishedAt: String? = null,
    val succeeded: Boolean? = null,
    val summary: String? = null,
    val llmModel: String? = null,
    val reasoning: Boolean? = null,
    val vision: Boolean? = null,
    val maxSteps: Int? = null,
    val temperature: Double? = null,
    val executionTimeout: Int? = null,
)

data class PortalTaskScreenshotSet(
    val urls: List<String>,
) {
    val count: Int
        get() = urls.size

    val latestUrl: String?
        get() = urls.lastOrNull()
}

enum class PortalTaskNotificationPhase {
    NONE,
    RUNNING,
    CANCELLING,
    TERMINAL,
}

object PortalTaskTracking {
    const val STATUS_CREATED = "created"
    const val STATUS_RUNNING = "running"
    const val STATUS_PAUSED = "paused"
    const val STATUS_COMPLETED = "completed"
    const val STATUS_FAILED = "failed"
    const val STATUS_CANCELLED = "cancelled"
    const val STATUS_CANCELLING = "cancelling"
    const val STATUS_TRACKING_TIMEOUT = "tracking_timeout"

    fun computePollDeadline(startedAtMs: Long, executionTimeoutSec: Int): Long {
        return startedAtMs + executionTimeoutSec.coerceAtLeast(1) * 1000L
    }

    fun buildPromptPreview(prompt: String, maxLength: Int = 80): String {
        val normalized = prompt.trim().replace(Regex("\\s+"), " ")
        if (normalized.length <= maxLength) return normalized
        return normalized.take(maxLength - 1).trimEnd() + "…"
    }

    fun isTerminalStatus(status: String?): Boolean {
        return status == STATUS_COMPLETED ||
            status == STATUS_FAILED ||
            status == STATUS_CANCELLED
    }

    fun isBlockingStatus(status: String?): Boolean {
        return status == STATUS_CREATED ||
            status == STATUS_RUNNING ||
            status == STATUS_PAUSED ||
            status == STATUS_CANCELLING
    }

    fun hasReachedPollingDeadline(record: PortalActiveTaskRecord, nowMs: Long): Boolean {
        return record.pollDeadlineMs > 0 && nowMs >= record.pollDeadlineMs
    }

    fun isLocalTerminalStatus(status: String?): Boolean {
        return isTerminalStatus(status) || status == STATUS_TRACKING_TIMEOUT
    }

    fun shouldShowTerminalToast(record: PortalActiveTaskRecord?): Boolean {
        if (record == null) return false
        return isLocalTerminalStatus(record.lastStatus) && !record.terminalToastShown
    }

    fun shouldHandleTerminalTransition(record: PortalActiveTaskRecord?): Boolean {
        if (record == null) return false
        return isLocalTerminalStatus(record.lastStatus) && !record.terminalTransitionHandled
    }

    fun shouldShowStartedToast(record: PortalActiveTaskRecord?): Boolean {
        if (record == null) return false
        return (record.lastStatus == STATUS_CREATED || record.lastStatus == STATUS_RUNNING) &&
            !record.startedToastShown
    }

    fun withUpdatedStatus(record: PortalActiveTaskRecord, status: String): PortalActiveTaskRecord {
        if (record.lastStatus == status) return record

        val resetTerminalFlags = isBlockingStatus(status) || isLocalTerminalStatus(status)
        return record.copy(
            lastStatus = status,
            terminalToastShown = if (resetTerminalFlags) false else record.terminalToastShown,
            terminalReturnHandled = if (resetTerminalFlags) false else record.terminalReturnHandled,
            terminalTransitionHandled = if (resetTerminalFlags) false else record.terminalTransitionHandled,
        )
    }

    fun notificationPhaseForStatus(status: String?): PortalTaskNotificationPhase {
        return when {
            status == STATUS_CANCELLING -> PortalTaskNotificationPhase.CANCELLING
            isBlockingStatus(status) -> PortalTaskNotificationPhase.RUNNING
            isTerminalStatus(status) -> PortalTaskNotificationPhase.TERMINAL
            else -> PortalTaskNotificationPhase.NONE
        }
    }
}
