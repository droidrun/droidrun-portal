package com.mobilerun.portal.taskprompt

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.mobilerun.portal.R
import com.mobilerun.portal.ui.MainActivity
import androidx.core.net.toUri

object TaskPromptNotificationManager {
    const val ACTION_CANCEL_TASK = "com.mobilerun.portal.taskprompt.action.CANCEL_TASK"
    const val ACTION_TASK_STATE_CHANGED = "com.mobilerun.portal.taskprompt.action.TASK_STATE_CHANGED"
    const val EXTRA_TASK_ID = "extra_task_id"

    private const val CHANNEL_ID = "task_prompt_channel"
    private const val CHANNEL_NAME = "Portal Tasks"
    private const val NOTIFICATION_ID = 3101
    private const val TAG = "TaskPromptNotif"

    fun showActiveTask(context: Context, record: PortalActiveTaskRecord) {
        val phase = PortalTaskTracking.notificationPhaseForStatus(record.lastStatus)
        if (phase == PortalTaskNotificationPhase.NONE || phase == PortalTaskNotificationPhase.TERMINAL) {
            Log.d(TAG, "Active notification suppressed for taskId=${record.taskId} phase=$phase")
            cancel(context)
            return
        }

        Log.d(TAG, "Posting active task notification for taskId=${record.taskId} phase=$phase")
        notify(
            context = context,
            title = if (phase == PortalTaskNotificationPhase.CANCELLING) {
                context.getString(R.string.task_prompt_notification_cancelling_title)
            } else {
                context.getString(R.string.task_prompt_notification_running_title)
            },
            message = buildNotificationMessage(context, record),
            ongoing = true,
            includeCancelAction = phase != PortalTaskNotificationPhase.CANCELLING,
            taskId = record.taskId,
        )
    }

    fun showTerminalTask(
        context: Context,
        record: PortalActiveTaskRecord,
        details: PortalTaskDetails?,
        fallbackMessage: String,
    ) {
        val title = when (record.lastStatus) {
            PortalTaskTracking.STATUS_COMPLETED -> context.getString(R.string.task_prompt_notification_completed_title)
            PortalTaskTracking.STATUS_FAILED -> context.getString(R.string.task_prompt_notification_failed_title)
            PortalTaskTracking.STATUS_CANCELLED -> context.getString(R.string.task_prompt_notification_cancelled_title)
            else -> context.getString(R.string.task_prompt_notification_running_title)
        }

        val message = details?.summary?.takeIf { it.isNotBlank() }
            ?: buildTerminalFallbackMessage(context, record, details)
            ?: fallbackMessage

        Log.d(TAG, "Posting terminal task notification for taskId=${record.taskId} status=${record.lastStatus}")
        notify(
            context = context,
            title = title,
            message = message,
            ongoing = false,
            includeCancelAction = false,
            taskId = record.taskId,
        )
    }

    fun cancel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        Log.d(TAG, "Cancelling task notification")
        manager.cancel(NOTIFICATION_ID)
    }

    fun broadcastTaskStateChanged(context: Context) {
        context.sendBroadcast(
            Intent(ACTION_TASK_STATE_CHANGED).setPackage(context.packageName),
        )
    }

    private fun notify(
        context: Context,
        title: String,
        message: String,
        ongoing: Boolean,
        includeCancelAction: Boolean,
        taskId: String,
    ) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        createNotificationChannel(manager)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(buildOpenPortalPendingIntent(context))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)

        if (includeCancelAction) {
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                context.getString(R.string.task_prompt_cancel_button),
                buildCancelPendingIntent(context, taskId),
            )
        }

        try {
            manager.notify(NOTIFICATION_ID, builder.build())
        } catch (error: SecurityException) {
            Log.w(TAG, "Posting task notification failed.", error)
        }
    }

    private fun createNotificationChannel(manager: NotificationManager) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    private fun buildOpenPortalPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            31,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun buildCancelPendingIntent(context: Context, taskId: String): PendingIntent {
        val intent = Intent(context, TaskPromptNotificationActionReceiver::class.java).apply {
            action = ACTION_CANCEL_TASK
            data = "portal-task://cancel/${Uri.encode(taskId)}".toUri()
            putExtra(EXTRA_TASK_ID, taskId)
        }
        return PendingIntent.getBroadcast(
            context,
            taskId.hashCode() and 0x7fffffff,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun buildNotificationMessage(
        context: Context,
        record: PortalActiveTaskRecord,
    ): String {
        val preview = record.promptPreview.ifBlank {
            context.getString(R.string.task_prompt_notification_default_message)
        }
        return "$preview\n${context.getString(R.string.task_prompt_task_id_label)}: ${record.taskId}"
    }

    private fun buildTerminalFallbackMessage(
        context: Context,
        record: PortalActiveTaskRecord,
        details: PortalTaskDetails?,
    ): String? {
        val steps = details?.steps
        return when (record.lastStatus) {
            PortalTaskTracking.STATUS_COMPLETED -> {
                if (steps != null) {
                    context.getString(R.string.task_prompt_completed_steps, steps)
                } else {
                    context.getString(R.string.task_prompt_completed_generic)
                }
            }

            PortalTaskTracking.STATUS_FAILED -> {
                if (steps != null) {
                    context.getString(R.string.task_prompt_failed_steps, steps)
                } else {
                    context.getString(R.string.task_prompt_failed_generic)
                }
            }

            PortalTaskTracking.STATUS_CANCELLED -> context.getString(R.string.task_prompt_cancelled_generic)
            else -> null
        }
    }
}
