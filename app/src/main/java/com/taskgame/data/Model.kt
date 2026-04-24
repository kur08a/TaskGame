package com.taskgame.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

enum class TaskDifficulty(val reward: Int) {
    Low(1),
    Medium(2),
    High(3)
}

enum class TaskStatus {
    Pending,
    InProgress,
    Completed,
    Overdue
}

@Entity(tableName = "app_settings")
data class AppSettingsEntity(
    @PrimaryKey val id: Int = 1,
    val username: String = "用户",
    val score: Int = 100,
    val lockEnabled: Boolean = false,
    val passwordHash: String = "",
    val passwordInitialized: Boolean = false,
    val securityPromptDone: Boolean = false
)

@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val difficulty: TaskDifficulty,
    val deadlineMillis: Long,
    val priority: Int,
    val status: TaskStatus,
    val description: String = "",
    val createdAtMillis: Long = System.currentTimeMillis(),
    val overduePenaltyApplied: Boolean = false,
    val notified1h: Boolean = false,
    val notified15m: Boolean = false
)

@Entity(
    tableName = "subtasks",
    foreignKeys = [
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("taskId")]
)
data class SubTaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: Long,
    val name: String,
    val completed: Boolean = false
)

data class TaskWithSubTasks(
    val task: TaskEntity,
    val subTasks: List<SubTaskEntity>
)

@Dao
interface TaskGameDao {
    @Query("SELECT * FROM app_settings WHERE id = 1")
    fun observeSettings(): Flow<AppSettingsEntity?>

    @Query("SELECT * FROM app_settings WHERE id = 1")
    suspend fun getSettings(): AppSettingsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSettings(settings: AppSettingsEntity)

    @Query("SELECT * FROM tasks ORDER BY deadlineMillis ASC, priority ASC")
    fun observeTasks(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM subtasks WHERE taskId = :taskId")
    suspend fun getSubTasksByTask(taskId: Long): List<SubTaskEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(task: TaskEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSubTasks(subTasks: List<SubTaskEntity>)

    @Query("UPDATE tasks SET status = :status WHERE id = :taskId")
    suspend fun updateTaskStatus(taskId: Long, status: TaskStatus)

    @Query("UPDATE tasks SET notified1h = :sent WHERE id = :taskId")
    suspend fun updateNotified1h(taskId: Long, sent: Boolean = true)

    @Query("UPDATE tasks SET notified15m = :sent WHERE id = :taskId")
    suspend fun updateNotified15m(taskId: Long, sent: Boolean = true)

    @Query("UPDATE subtasks SET completed = 1 WHERE id = :subTaskId")
    suspend fun completeSubTask(subTaskId: Long)

    @Query("DELETE FROM tasks WHERE id = :taskId")
    suspend fun deleteTask(taskId: Long)

    @Query("UPDATE tasks SET deadlineMillis = :newDeadline, status = :status, overduePenaltyApplied = 1 WHERE id = :taskId")
    suspend fun reviveTask(taskId: Long, newDeadline: Long, status: TaskStatus = TaskStatus.InProgress)

    @Query("UPDATE tasks SET status = :status, overduePenaltyApplied = 1 WHERE id = :taskId")
    suspend fun markOverdueAndPenalty(taskId: Long, status: TaskStatus = TaskStatus.Overdue)

    @Query("SELECT * FROM tasks WHERE status IN ('Pending', 'InProgress')")
    suspend fun getActiveTasks(): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE id = :taskId")
    suspend fun getTask(taskId: Long): TaskEntity?

    @Transaction
    suspend fun getTaskWithSubTasks(taskId: Long): TaskWithSubTasks? {
        val task = getTask(taskId) ?: return null
        val subTasks = getSubTasksByTask(taskId)
        return TaskWithSubTasks(task, subTasks)
    }
}

@Database(
    entities = [AppSettingsEntity::class, TaskEntity::class, SubTaskEntity::class],
    version = 3,
    exportSchema = false
)
abstract class TaskGameDatabase : RoomDatabase() {
    abstract fun dao(): TaskGameDao
}
