package com.taskgame

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object TaskReminderNotifier {
    fun showReminder(context: Context, taskId: Long, taskName: String, type: String): Boolean {
        if (taskId <= 0 || taskName.isBlank()) return false
        ensureChannel(context)

        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            return false
        }

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingOpenIntent = PendingIntent.getActivity(
            context,
            taskId.toInt(),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val content = if (type == TaskDeadlineReminderReceiver.TYPE_OVERDUE) {
            "TaskGame任务“$taskName”已逾期，请尽快处理"
        } else {
            "TaskGame任务“$taskName”还有30分钟截止"
        }
        val notificationId = (taskId * 10 + if (type == TaskDeadlineReminderReceiver.TYPE_OVERDUE) 4 else 3).toInt()
        val builder = NotificationCompat.Builder(context, TaskDeadlineReminderReceiver.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("TaskGame任务提醒")
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setShowWhen(true)
            .setWhen(System.currentTimeMillis())
            .setContentIntent(pendingOpenIntent)

        NotificationManagerCompat.from(context).notify(notificationId, builder.build())
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val active = runCatching {
            if (Build.VERSION.SDK_INT >= 23) {
                manager.activeNotifications.any { it.id == notificationId }
            } else {
                true
            }
        }.getOrDefault(false)
        return true
    }

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val existing = manager.getNotificationChannel(TaskDeadlineReminderReceiver.CHANNEL_ID)
        if (existing == null) {
            val channel = NotificationChannel(
                TaskDeadlineReminderReceiver.CHANNEL_ID,
                "TaskGame消息提醒",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "任务到期与临期消息提醒"
                enableVibration(true)
                setShowBadge(true)
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
            manager.createNotificationChannel(channel)
        }
    }
}
