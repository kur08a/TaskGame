package com.taskgame

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class TaskDeadlineReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getLongExtra(EXTRA_TASK_ID, -1L)
        val taskName = intent.getStringExtra(EXTRA_TASK_NAME).orEmpty()
        val type = intent.getStringExtra(EXTRA_TYPE).orEmpty()
        if (taskId <= 0 || taskName.isBlank()) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val sent = TaskReminderNotifier.showReminder(context, taskId, taskName, type)
                if (sent) {
                    TaskNotificationScheduler.markReminderSent(context, taskId, type)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val CHANNEL_ID = "taskgame_message_channel_v2"
        const val EXTRA_TASK_ID = "extra_task_id"
        const val EXTRA_TASK_NAME = "extra_task_name"
        const val EXTRA_TYPE = "extra_type"
        const val TYPE_30M = "30m"
        const val TYPE_OVERDUE = "overdue"
    }
}
