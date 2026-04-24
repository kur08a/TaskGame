package com.taskgame

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.room.Room
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.taskgame.data.TaskEntity
import com.taskgame.data.TaskGameDatabase
import com.taskgame.data.TaskStatus
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SupportFactory

object TaskNotificationScheduler {
    private const val PERIODIC_WORK_NAME = "task_reminder_periodic_work"
    private const val IMMEDIATE_WORK_NAME = "task_reminder_immediate_work"

    fun rescheduleAllInBackground(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                ensureWorkerScheduled(context)
                val dao = openDb(context).dao()
                dao.getActiveTasks().forEach { task ->
                    scheduleForTask(context, task)
                }
                triggerImmediateCheck(context)
            }
        }
    }

    fun scheduleForTask(context: Context, task: TaskEntity) {
        runCatching {
            ensureWorkerScheduled(context)
            if (task.status != TaskStatus.Pending && task.status != TaskStatus.InProgress) {
                cancelTask(context, task.id)
                return
            }
            if (!task.notified15m) {
                schedule30mAlarm(context, task)
            }
            triggerImmediateCheck(context)
        }
    }

    fun ensureWorkerScheduled(context: Context) {
        val request = PeriodicWorkRequestBuilder<TaskReminderWorker>(15, TimeUnit.MINUTES)
            .setInitialDelay(1, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun triggerImmediateCheck(context: Context) {
        val request = OneTimeWorkRequestBuilder<TaskReminderWorker>().build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            IMMEDIATE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    fun cancelTask(context: Context, taskId: Long) {
        runCatching {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            listOf(
                TaskDeadlineReminderReceiver.TYPE_30M
            ).forEach { type ->
                val pi = PendingIntent.getBroadcast(
                    context,
                    requestCode(taskId, type),
                    preDeadlineIntent(context, taskId),
                    PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
                )
                pi?.let {
                    alarmManager.cancel(it)
                    it.cancel()
                }
            }
        }
    }

    private fun schedule30mAlarm(context: Context, task: TaskEntity) {
        val now = System.currentTimeMillis()
        val triggerAt = task.deadlineMillis - 30 * 60 * 1000L
        if (triggerAt <= now || task.deadlineMillis <= now) return
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(
            context,
            requestCode(task.id, TaskDeadlineReminderReceiver.TYPE_30M),
            preDeadlineIntent(context, task.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val showIntent = PendingIntent.getActivity(
            context,
            requestCode(task.id, "30m_show"),
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, showIntent), pi)
        } catch (_: Exception) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
    }

    fun markReminderSent(context: Context, taskId: Long, type: String) {
        CoroutineScope(Dispatchers.IO).launch {
            val dao = openDb(context).dao()
            when (type) {
                TaskDeadlineReminderReceiver.TYPE_30M -> dao.updateNotified15m(taskId, true)
            }
        }
    }

    private fun preDeadlineIntent(context: Context, taskId: Long): Intent {
        return Intent(context, TaskOverdueAlarmReceiver::class.java).apply {
            putExtra(TaskDeadlineReminderReceiver.EXTRA_TASK_ID, taskId)
        }
    }

    private fun requestCode(taskId: Long, type: String): Int {
        val suffix = when (type) {
            TaskDeadlineReminderReceiver.TYPE_30M -> 3
            else -> 9
        }
        return (taskId * 10 + suffix).toInt()
    }

    private fun openDb(context: Context): TaskGameDatabase {
        SQLiteDatabase.loadLibs(context)
        val passphrase = SQLiteDatabase.getBytes("TaskGameLocalEncryptedDb".toCharArray())
        return Room.databaseBuilder(context, TaskGameDatabase::class.java, "task_game.db")
            .openHelperFactory(SupportFactory(passphrase))
            .fallbackToDestructiveMigration()
            .build()
    }
}
