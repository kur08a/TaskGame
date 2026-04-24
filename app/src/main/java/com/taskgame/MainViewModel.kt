package com.taskgame

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.taskgame.data.DashboardState
import com.taskgame.data.TaskDifficulty
import com.taskgame.data.TaskGameRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(private val repository: TaskGameRepository) : ViewModel() {
    val dashboard: StateFlow<DashboardState> =
        repository.dashboardFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DashboardState())

    val message = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch {
            repository.bootstrap()
        }
        viewModelScope.launch {
            while (true) {
                repository.refreshOverdueAndScore()
                delay(60_000)
            }
        }
    }

    fun clearMessage() {
        message.value = null
    }

    fun createTask(
        name: String,
        difficulty: TaskDifficulty,
        deadlineMillis: Long,
        priority: Int,
        description: String,
        subTasks: List<String>
    ) {
        viewModelScope.launch {
            repository.createTask(name, difficulty, deadlineMillis, priority, description, subTasks)
            message.value = "任务创建成功"
        }
    }

    fun startTask(taskId: Long) {
        viewModelScope.launch { repository.startTask(taskId) }
    }

    fun completeTask(taskId: Long) {
        viewModelScope.launch { repository.completeTask(taskId) }
    }

    fun deleteTask(taskId: Long) {
        viewModelScope.launch {
            repository.deleteTask(taskId)
            message.value = "任务已删除"
        }
    }

    fun completeSubTask(subTaskId: Long) {
        viewModelScope.launch { repository.completeSubTask(subTaskId) }
    }

    fun saveUsername(name: String) {
        viewModelScope.launch {
            repository.saveUsername(name)
            message.value = "用户名已保存"
        }
    }

    fun setupPassword(password: String, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            onDone(repository.initPassword(password))
        }
    }

    fun verifyPassword(password: String, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            onDone(repository.verifyPassword(password))
        }
    }

    fun setLockEnabled(enabled: Boolean) {
        viewModelScope.launch { repository.setLockEnabled(enabled) }
    }

    fun setSecurityPromptDone(done: Boolean) {
        viewModelScope.launch { repository.setSecurityPromptDone(done) }
    }

    fun changePassword(oldPassword: String, newPassword: String, onDone: (Boolean) -> Unit) {
        viewModelScope.launch { onDone(repository.changePassword(oldPassword, newPassword)) }
    }

    fun reviveTask(taskId: Long, nDays: Int) {
        viewModelScope.launch {
            val result = repository.reviveTask(taskId, nDays)
            message.value = result.exceptionOrNull()?.message ?: "任务复活成功"
        }
    }

    fun refreshTimeSensitiveState() {
        viewModelScope.launch {
            repository.refreshOverdueAndScore()
        }
    }
}

class MainViewModelFactory(private val repository: TaskGameRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return MainViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
