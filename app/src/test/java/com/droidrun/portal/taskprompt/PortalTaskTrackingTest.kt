package com.droidrun.portal.taskprompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PortalTaskTrackingTest {

    @Test
    fun computePollDeadline_usesExecutionTimeoutSeconds() {
        assertEquals(12_000L, PortalTaskTracking.computePollDeadline(2_000L, 10))
    }

    @Test
    fun hasReachedPollingDeadline_returnsTrueAtDeadline() {
        val record = PortalActiveTaskRecord(
            taskId = "task-123",
            promptPreview = "Prompt",
            startedAtMs = 1_000L,
            executionTimeoutSec = 5,
            pollDeadlineMs = 6_000L,
        )

        assertFalse(PortalTaskTracking.hasReachedPollingDeadline(record, 5_999L))
        assertTrue(PortalTaskTracking.hasReachedPollingDeadline(record, 6_000L))
    }

    @Test
    fun notificationPhaseForStatus_mapsRunningCancellingAndTerminalStates() {
        assertEquals(
            PortalTaskNotificationPhase.RUNNING,
            PortalTaskTracking.notificationPhaseForStatus(PortalTaskTracking.STATUS_RUNNING),
        )
        assertEquals(
            PortalTaskNotificationPhase.CANCELLING,
            PortalTaskTracking.notificationPhaseForStatus(PortalTaskTracking.STATUS_CANCELLING),
        )
        assertEquals(
            PortalTaskNotificationPhase.TERMINAL,
            PortalTaskTracking.notificationPhaseForStatus(PortalTaskTracking.STATUS_COMPLETED),
        )
        assertEquals(
            PortalTaskNotificationPhase.NONE,
            PortalTaskTracking.notificationPhaseForStatus(PortalTaskTracking.STATUS_TRACKING_TIMEOUT),
        )
    }

    @Test
    fun shouldShowTerminalToast_onlyForLocalTerminalStates() {
        val running = PortalActiveTaskRecord(
            taskId = "task-running",
            promptPreview = "Prompt",
            startedAtMs = 0L,
            executionTimeoutSec = 10,
            pollDeadlineMs = 10_000L,
            lastStatus = PortalTaskTracking.STATUS_RUNNING,
            terminalToastShown = false,
        )
        val finished = running.copy(
            taskId = "task-finished",
            lastStatus = PortalTaskTracking.STATUS_COMPLETED,
        )
        val finishedToastShown = finished.copy(terminalToastShown = true)

        assertFalse(PortalTaskTracking.shouldShowTerminalToast(running))
        assertTrue(PortalTaskTracking.shouldShowTerminalToast(finished))
        assertFalse(PortalTaskTracking.shouldShowTerminalToast(finishedToastShown))
    }

    @Test
    fun shouldShowStartedToast_only_for_running_without_flag() {
        val running = PortalActiveTaskRecord(
            taskId = "task-running",
            promptPreview = "Prompt",
            startedAtMs = 0L,
            executionTimeoutSec = 10,
            pollDeadlineMs = 10_000L,
            lastStatus = PortalTaskTracking.STATUS_RUNNING,
            startedToastShown = false,
        )

        assertTrue(PortalTaskTracking.shouldShowStartedToast(running))
        assertFalse(PortalTaskTracking.shouldShowStartedToast(running.copy(startedToastShown = true)))
        assertTrue(
            PortalTaskTracking.shouldShowStartedToast(
                running.copy(lastStatus = PortalTaskTracking.STATUS_CREATED),
            ),
        )
        assertFalse(
            PortalTaskTracking.shouldShowStartedToast(
                running.copy(lastStatus = PortalTaskTracking.STATUS_COMPLETED),
            ),
        )
    }

    @Test
    fun buildPromptPreview_truncatesLongPromptWithEllipsis() {
        val preview = PortalTaskTracking.buildPromptPreview(
            "Open settings and make sure Wi-Fi and Bluetooth are enabled for the office setup flow",
            maxLength = 32,
        )

        assertEquals(32, preview.length)
        assertTrue(preview.endsWith("…"))
        assertTrue(preview.startsWith("Open settings and make sure"))
    }

    @Test
    fun buildActiveTaskRecord_carries_trigger_launch_metadata() {
        val record = PortalTaskLaunchCoordinator.buildActiveTaskRecord(
            prompt = "Open Slack",
            settings = PortalTaskSettings(),
            taskId = "task-123",
            metadata = PortalTaskLaunchMetadata(
                triggerRuleId = "rule-1",
                returnToPortalOnTerminal = true,
            ),
        )

        assertEquals("rule-1", record.triggerRuleId)
        assertTrue(record.returnToPortalOnTerminal)
        assertFalse(record.startedToastShown)
        assertFalse(record.terminalReturnHandled)
        assertFalse(record.terminalTransitionHandled)
    }

    @Test
    fun shouldReturnToPortal_for_terminal_states_once_without_requiring_trigger_origin() {
        val triggerRecord = PortalActiveTaskRecord(
            taskId = "task-123",
            promptPreview = "Prompt",
            startedAtMs = 0L,
            executionTimeoutSec = 30,
            pollDeadlineMs = 30_000L,
            lastStatus = PortalTaskTracking.STATUS_COMPLETED,
            triggerRuleId = "rule-1",
            returnToPortalOnTerminal = true,
        )
        val manualRecord = triggerRecord.copy(triggerRuleId = null)
        val alreadyHandled = triggerRecord.copy(terminalReturnHandled = true)

        assertTrue(PortalTaskStateMonitor.shouldReturnToPortal(triggerRecord))
        assertTrue(PortalTaskStateMonitor.shouldReturnToPortal(manualRecord))
        assertFalse(PortalTaskStateMonitor.shouldReturnToPortal(alreadyHandled))
        assertFalse(
            PortalTaskStateMonitor.shouldReturnToPortal(
                triggerRecord.copy(lastStatus = PortalTaskTracking.STATUS_CANCELLED),
            ),
        )
    }

    @Test
    fun shouldHandleTerminalTransition_only_for_unhandled_local_terminal_states() {
        val completed = PortalActiveTaskRecord(
            taskId = "task-terminal",
            promptPreview = "Prompt",
            startedAtMs = 0L,
            executionTimeoutSec = 15,
            pollDeadlineMs = 15_000L,
            lastStatus = PortalTaskTracking.STATUS_COMPLETED,
        )

        assertTrue(PortalTaskTracking.shouldHandleTerminalTransition(completed))
        assertFalse(
            PortalTaskTracking.shouldHandleTerminalTransition(
                completed.copy(terminalTransitionHandled = true),
            ),
        )
        assertFalse(
            PortalTaskTracking.shouldHandleTerminalTransition(
                completed.copy(lastStatus = PortalTaskTracking.STATUS_RUNNING),
            ),
        )
    }

    @Test
    fun withUpdatedStatus_resets_terminal_flags_for_new_blocking_or_terminal_states() {
        val record = PortalActiveTaskRecord(
            taskId = "task-status",
            promptPreview = "Prompt",
            startedAtMs = 0L,
            executionTimeoutSec = 15,
            pollDeadlineMs = 15_000L,
            lastStatus = PortalTaskTracking.STATUS_COMPLETED,
            terminalToastShown = true,
            terminalReturnHandled = true,
            terminalTransitionHandled = true,
        )

        val running = PortalTaskTracking.withUpdatedStatus(record, PortalTaskTracking.STATUS_RUNNING)
        assertEquals(PortalTaskTracking.STATUS_RUNNING, running.lastStatus)
        assertFalse(running.terminalToastShown)
        assertFalse(running.terminalReturnHandled)
        assertFalse(running.terminalTransitionHandled)

        val completedAgain =
            PortalTaskTracking.withUpdatedStatus(running, PortalTaskTracking.STATUS_COMPLETED)
        assertEquals(PortalTaskTracking.STATUS_COMPLETED, completedAgain.lastStatus)
        assertFalse(completedAgain.terminalToastShown)
        assertFalse(completedAgain.terminalReturnHandled)
        assertFalse(completedAgain.terminalTransitionHandled)
    }
}
