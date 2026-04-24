package com.taskgame

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.room.Room
import com.taskgame.data.TaskStatus
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SupportFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class TaskOverdueAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getLongExtra(TaskDeadlineReminderReceiver.EXTRA_TASK_ID, -1L)
        if (taskId <= 0) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dao = openDb(context).dao()
                val task = dao.getTask(taskId) ?: return@launch
                if (task.status != TaskStatus.Pending && task.status != TaskStatus.InProgress) return@launch

                TaskReminderNotifier.showReminder(
                    context,
                    task.id,
                    task.name,
                    TaskDeadlineReminderReceiver.TYPE_30M
                )
                dao.updateNotified15m(taskId, true)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun openDb(context: Context) =
        Room.databaseBuilder(context, com.taskgame.data.TaskGameDatabase::class.java, "task_game.db")
            .openHelperFactory(SupportFactory(SQLiteDatabase.getBytes("TaskGameLocalEncryptedDb".toCharArray())))
            .fallbackToDestructiveMigration()
            .build()
}
