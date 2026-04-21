package com.taskgame.data

import android.content.Context
import androidx.room.Room
import java.security.MessageDigest
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SupportFactory

data class TaskUiModel(
    val task: TaskEntity,
    val subTasks: List<SubTaskEntity>,
    val completionPercent: Double?
)

data class DashboardState(
    val settings: AppSettingsEntity = AppSettingsEntity(),
    val tasks: List<TaskUiModel> = emptyList()
)

class TaskGameRepository private constructor(private val dao: TaskGameDao) {
    val dashboardFlow: Flow<DashboardState> =
        combine(dao.observeSettings(), dao.observeTasks()) { settings, tasks ->
            val validSettings = settings ?: AppSettingsEntity()
            val uiTasks = tasks.map { task ->
                val subTasks = dao.getSubTasksByTask(task.id)
                val percent = if (subTasks.isEmpty()) {
                    null
                } else {
                    val completed = subTasks.count { it.completed }
                    completed.toDouble() * 100.0 / subTasks.size
                }
                TaskUiModel(task, subTasks, percent)
            }
            DashboardState(validSettings, uiTasks)
        }

    suspend fun bootstrap() {
        if (dao.getSettings() == null) {
            dao.upsertSettings(AppSettingsEntity())
        }
        refreshOverdueAndScore()
    }

    suspend fun saveUsername(username: String) {
        val settings = dao.getSettings() ?: AppSettingsEntity()
        dao.upsertSettings(settings.copy(username = username))
    }

    suspend fun initPassword(password: String): Boolean {
        val settings = dao.getSettings() ?: AppSettingsEntity()
        if (settings.passwordInitialized) return false
        dao.upsertSettings(
            settings.copy(
                passwordHash = sha256(password),
                passwordInitialized = true,
                lockEnabled = true
            )
        )
        return true
    }

    suspend fun verifyPassword(password: String): Boolean {
        val settings = dao.getSettings() ?: return false
        if (!settings.passwordInitialized) return false
        return settings.passwordHash == sha256(password)
    }

    suspend fun setLockEnabled(enabled: Boolean) {
        val settings = dao.getSettings() ?: AppSettingsEntity()
        dao.upsertSettings(settings.copy(lockEnabled = enabled))
    }

    suspend fun setSecurityPromptDone(done: Boolean) {
        val settings = dao.getSettings() ?: AppSettingsEntity()
        dao.upsertSettings(settings.copy(securityPromptDone = done))
    }

    suspend fun changePassword(oldPassword: String, newPassword: String): Boolean {
        val settings = dao.getSettings() ?: return false
        if (settings.passwordHash != sha256(oldPassword)) return false
        dao.upsertSettings(settings.copy(passwordHash = sha256(newPassword), passwordInitialized = true))
        return true
    }

    suspend fun createTask(
        name: String,
        difficulty: TaskDifficulty,
        deadlineMillis: Long,
        priority: Int,
        description: String,
        subTasks: List<String>
    ) {
        val id = dao.insertTask(
            TaskEntity(
                name = name,
                difficulty = difficulty,
                deadlineMillis = deadlineMillis,
                priority = priority,
                status = TaskStatus.Pending,
                description = description.trim()
            )
        )
        if (subTasks.isNotEmpty()) {
            dao.insertSubTasks(subTasks.filter { it.isNotBlank() }.map { SubTaskEntity(taskId = id, name = it.trim()) })
        }
    }

    suspend fun startTask(taskId: Long) = dao.updateTaskStatus(taskId, TaskStatus.InProgress)

    suspend fun completeTask(taskId: Long) {
        val task = dao.getTask(taskId) ?: return
        if (task.status != TaskStatus.InProgress) return
        dao.updateTaskStatus(taskId, TaskStatus.Completed)
        val settings = dao.getSettings() ?: AppSettingsEntity()
        val score = settings.score + task.difficulty.reward
        dao.upsertSettings(settings.copy(score = score))
    }

    suspend fun deleteTask(taskId: Long) = dao.deleteTask(taskId)

    suspend fun completeSubTask(subTaskId: Long) = dao.completeSubTask(subTaskId)

    suspend fun refreshOverdueAndScore() {
        val now = currentMinuteMillis()
        val settings = dao.getSettings() ?: AppSettingsEntity()
        var score = settings.score
        val activeTasks = dao.getActiveTasks().sortedBy { it.deadlineMillis }
        activeTasks.forEach { task ->
            if (now >= task.deadlineMillis) {
                if (!task.overduePenaltyApplied) {
                    score = (score - task.difficulty.reward).coerceAtLeast(0)
                    dao.markOverdueAndPenalty(task.id)
                } else {
                    dao.updateTaskStatus(task.id, TaskStatus.Overdue)
                }
            }
        }
        if (score != settings.score) {
            dao.upsertSettings(settings.copy(score = score))
        }
    }

    suspend fun reviveTask(taskId: Long, nDays: Int): Result<Unit> {
        if (nDays !in 1..365) {
            return Result.failure(IllegalArgumentException("请输入1到365之间的某个正整数"))
        }
        val task = dao.getTask(taskId) ?: return Result.failure(IllegalArgumentException("任务不存在"))
        if (task.status != TaskStatus.Overdue) return Result.failure(IllegalArgumentException("仅已逾期任务可复活"))
        val settings = dao.getSettings() ?: AppSettingsEntity()
        val cost = task.difficulty.reward + nDays
        if (settings.score < cost) return Result.failure(IllegalStateException("积分不足，无法复活"))
        val newDeadline = currentMinuteMillis() + nDays * 24L * 60L * 60L * 1000L
        dao.reviveTask(taskId, newDeadline)
        dao.upsertSettings(settings.copy(score = settings.score - cost))
        return Result.success(Unit)
    }

    companion object {
        @Volatile
        private var instance: TaskGameRepository? = null

        fun getInstance(context: Context): TaskGameRepository {
            return instance ?: synchronized(this) {
                instance ?: TaskGameRepository(buildDb(context).dao()).also { instance = it }
            }
        }

        private fun buildDb(context: Context): TaskGameDatabase {
            SQLiteDatabase.loadLibs(context)
            val passphrase = SQLiteDatabase.getBytes("TaskGameLocalEncryptedDb".toCharArray())
            return Room.databaseBuilder(context, TaskGameDatabase::class.java, "task_game.db")
                .openHelperFactory(SupportFactory(passphrase))
                .fallbackToDestructiveMigration()
                .build()
        }
    }
}

fun currentMinuteMillis(): Long {
    val now = System.currentTimeMillis()
    return now - now % 60000L
}

fun formatDateTime(millis: Long): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    return sdf.format(Date(millis))
}

fun parseDateTime(text: String): Long? {
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    sdf.isLenient = false
    return try {
        val date = sdf.parse(text) ?: return null
        date.time - (date.time % 60000L)
    } catch (_: ParseException) {
        null
    }
}

fun sha256(raw: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
    return digest.joinToString("") { "%02x".format(it) }
}
