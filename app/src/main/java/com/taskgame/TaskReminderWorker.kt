package com.taskgame

import android.content.Context
import androidx.room.Room
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.taskgame.data.AppSettingsEntity
import com.taskgame.data.TaskStatus
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SupportFactory

class TaskReminderWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return runCatching {
            val dao = openDb(applicationContext).dao()
            val now = System.currentTimeMillis()
            val activeTasks = dao.getActiveTasks().sortedBy { it.deadlineMillis }
            val settings = dao.getSettings() ?: AppSettingsEntity()
            var score = settings.score

            activeTasks.forEach { task ->
                if (task.status != TaskStatus.Pending && task.status != TaskStatus.InProgress) return@forEach
                if (now >= task.deadlineMillis) {
                    if (!task.overduePenaltyApplied) {
                        score = (score - task.difficulty.reward).coerceAtLeast(0)
                        dao.markOverdueAndPenalty(task.id)
                    } else {
                        dao.updateTaskStatus(task.id, TaskStatus.Overdue)
                    }
                    if (!task.notified1h) {
                        val sent = TaskReminderNotifier.showReminder(
                            applicationContext,
                            task.id,
                            task.name,
                            TaskDeadlineReminderReceiver.TYPE_OVERDUE
                        )
                        if (sent) dao.updateNotified1h(task.id, true)
                    }
                    return@forEach
                }

                val shouldNotify30m = !task.notified15m && now >= task.deadlineMillis - 30 * 60 * 1000L
                if (shouldNotify30m) {
                    val sent = TaskReminderNotifier.showReminder(
                        applicationContext,
                        task.id,
                        task.name,
                        TaskDeadlineReminderReceiver.TYPE_30M
                    )
                    if (sent) dao.updateNotified15m(task.id, true)
                }
            }
            if (score != settings.score) {
                dao.upsertSettings(settings.copy(score = score))
            }
        }.fold(
            onSuccess = { Result.success() },
            onFailure = { Result.retry() }
        )
    }

    private fun openDb(context: Context) =
        Room.databaseBuilder(
            context,
            com.taskgame.data.TaskGameDatabase::class.java,
            "task_game.db"
        )
            .openHelperFactory(SupportFactory(SQLiteDatabase.getBytes("TaskGameLocalEncryptedDb".toCharArray())))
            .fallbackToDestructiveMigration()
            .build()
}
