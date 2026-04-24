package com.taskgame

import android.app.Activity
import android.app.AlarmManager
import android.app.DatePickerDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.widget.NumberPicker
import android.app.TimePickerDialog
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.core.content.edit
import com.taskgame.data.TaskDifficulty
import com.taskgame.data.TaskGameRepository
import com.taskgame.data.TaskStatus
import com.taskgame.data.TaskUiModel
import com.taskgame.data.currentMinuteMillis
import com.taskgame.data.formatDateTime
import java.util.Calendar
import java.util.Locale
import androidx.core.net.toUri
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels {
        MainViewModelFactory(TaskGameRepository.getInstance(applicationContext))
    }
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted && android.os.Build.VERSION.SDK_INT >= 33) {
                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                }
                runCatching { startActivity(intent) }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TaskNotificationScheduler.ensureWorkerScheduled(applicationContext)
        TaskNotificationScheduler.triggerImmediateCheck(applicationContext)
        requestNotificationPermissionIfNeeded()
        requestExactAlarmPermissionIfNeeded()
        setContent {
            val dashboard by vm.dashboard.collectAsStateWithLifecycle()
            val nav = rememberNavController()

            NavHost(navController = nav, startDestination = "splash") {
                composable("splash") {
                    SplashScreen {
                        if (dashboard.settings.lockEnabled) nav.navigate("unlock") else nav.navigate("home")
                    }
                }
                composable("unlock") {
                    UnlockScreen(
                        onUnlock = { pwd, callback ->
                            vm.verifyPassword(pwd) { ok ->
                                callback(ok)
                            }
                        },
                        onUnlockSuccess = {
                            nav.navigate("home") {
                                popUpTo("unlock") { inclusive = true }
                            }
                        }
                    )
                }
                composable("home") {
                    HomeScreen(
                        vm = vm,
                        onCreate = { nav.navigate("create") },
                        onProfile = { nav.navigate("profile") },
                        onRevive = { id -> nav.navigate("revive/$id") }
                    )
                }
                composable("create") {
                    CreateTaskScreen(
                        onBack = { nav.popBackStack() },
                        onSubmit = { n, d, dl, p, desc, subs ->
                            vm.createTask(n, d, dl, p, desc, subs)
                            nav.popBackStack()
                        }
                    )
                }
                composable("profile") {
                    ProfileScreen(
                        vm = vm,
                        activity = this@MainActivity,
                        onBack = { nav.popBackStack() }
                    )
                }
                composable(
                    route = "revive/{taskId}",
                    arguments = listOf(navArgument("taskId") { type = NavType.LongType })
                ) {
                    val taskId = it.arguments?.getLong("taskId") ?: 0L
                    ReviveDialog(
                        vm = vm,
                        taskId = taskId,
                        onDone = { nav.popBackStack() }
                    )
                }
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            val granted = checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun requestExactAlarmPermissionIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
            if (!alarmManager.canScheduleExactAlarms()) {
                val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                    data = "package:$packageName".toUri()
                }
                runCatching { startActivity(intent) }
            }
        }
    }

}

@Composable
private fun SplashScreen(onDone: () -> Unit) {
    LaunchedEffect(Unit) {
        delay(2000)
        onDone()
    }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("TaskGame", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(12.dp))
            Text("本地离线版")
        }
    }
}

@Composable
private fun UnlockScreen(
    onUnlock: (String, (Boolean) -> Unit) -> Unit,
    onUnlockSuccess: () -> Unit
) {
    var pwd by remember { mutableStateOf("") }
    var tipDialogText by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(tipDialogText) {
        val tip = tipDialogText ?: return@LaunchedEffect
        delay(1000)
        tipDialogText = null
        if (tip == "解锁成功") {
            onUnlockSuccess()
        }
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(16.dp)) {
            OutlinedTextField(
                value = pwd,
                onValueChange = { pwd = it },
                label = { Text("请输入解锁密码") },
                visualTransformation = PasswordVisualTransformation()
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(onClick = {
                onUnlock(pwd) { ok ->
                    tipDialogText = if (ok) "解锁成功" else "密码错误"
                }
            }) { Text("确认解锁") }
        }
    }
    if (tipDialogText != null) {
        AlertDialog(
            onDismissRequest = { },
            confirmButton = {},
            title = { Text(tipDialogText!!) },
            text = { Text("请稍候...") }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(
    vm: MainViewModel,
    onCreate: () -> Unit,
    onProfile: () -> Unit,
    onRevive: (Long) -> Unit
) {
    val context = LocalContext.current
    val dashboard by vm.dashboard.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var tab by remember { mutableStateOf(TaskStatus.Pending) }
    var expandedTask by remember { mutableStateOf<Long?>(null) }
    var deleteTarget by remember { mutableStateOf<Long?>(null) }
    var deletePwd by remember { mutableStateOf("") }
    var firstPromptDialog by remember { mutableStateOf(false) }
    var pageTip by remember { mutableStateOf<String?>(null) }
    var backgroundPopupPromptDialog by remember { mutableStateOf(false) }
    val closeDeleteDialog = {
        if (deleteTarget != null) {
            deleteTarget = null
        }
    }
    val closeFirstPromptDialog = {
        if (firstPromptDialog) {
            firstPromptDialog = false
        }
    }
    val nowMillis by produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            vm.refreshTimeSensitiveState()
            delay(30_000)
        }
    }
    val imminentTaskIds = dashboard.tasks
        .filter {
            (it.task.status == TaskStatus.Pending || it.task.status == TaskStatus.InProgress) &&
                it.task.deadlineMillis > nowMillis &&
                it.task.deadlineMillis - nowMillis < 30 * 60 * 1000L
        }
        .map { it.task.id }
        .toSet()

    LaunchedEffect(message) {
        if (!message.isNullOrBlank()) {
            pageTip = message
            vm.clearMessage()
        }
    }
    LaunchedEffect(pageTip) {
        if (pageTip != null) {
            delay(1000)
            pageTip = null
        }
    }

    LaunchedEffect(dashboard.settings.passwordInitialized, dashboard.settings.securityPromptDone) {
        if (!dashboard.settings.passwordInitialized && !dashboard.settings.securityPromptDone) {
            firstPromptDialog = true
        }
    }
    LaunchedEffect(Unit) {
        if (shouldShowBackgroundPopupPrompt(context)) {
            backgroundPopupPromptDialog = true
        }
    }

    Scaffold(
        topBar = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(dashboard.settings.username.ifBlank { "用户" }, fontWeight = FontWeight.Bold)
                Text("积分：${dashboard.settings.score}", fontWeight = FontWeight.Bold)
            }
        },
        bottomBar = {
            Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onProfile) {
                    Icon(Icons.Default.Person, contentDescription = null)
                    Text("个人中心")
                }
                Spacer(modifier = Modifier.weight(1f))
                Button(onClick = onCreate) { Text("+ 新建任务") }
            }
        }
    ) { padding ->
        val currentTasks = dashboard.tasks.filter { it.task.status == tab }
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (imminentTaskIds.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF0F0))
                ) {
                    Text(
                        "你有 ${imminentTaskIds.size} 个任务即将逾期，请尽快处理",
                        modifier = Modifier.padding(10.dp),
                        color = Color(0xFFB00020),
                        fontWeight = FontWeight.Medium
                    )
                }
            }
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
                listOf(TaskStatus.Pending, TaskStatus.InProgress, TaskStatus.Completed, TaskStatus.Overdue).forEach { status ->
                    val count = dashboard.tasks.count { it.task.status == status }
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 4.dp)
                            .clickable { tab = status },
                        colors = CardDefaults.cardColors(
                            containerColor = if (tab == status) Color(0xFFE8F1FF) else Color(0xFFF2F2F2)
                        )
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(statusLabel(status), fontWeight = FontWeight.Bold)
                            Text("$count")
                        }
                    }
                }
            }
            if (currentTasks.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("暂无${statusLabel(tab)}任务")
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(currentTasks, key = { it.task.id }) { model ->
                        TaskCard(
                            model = model,
                            expanded = expandedTask == model.task.id,
                            onToggle = {
                                expandedTask = if (expandedTask == model.task.id) null else model.task.id
                            },
                            onStart = { vm.startTask(model.task.id) },
                            onComplete = { vm.completeTask(model.task.id) },
                            onDelete = { deleteTarget = model.task.id },
                            onRevive = { onRevive(model.task.id) },
                            onSubTaskDone = vm::completeSubTask,
                            imminent = imminentTaskIds.contains(model.task.id)
                        )
                    }
                }
            }
        }
    }

    if (deleteTarget != null) {
        if (!dashboard.settings.lockEnabled) {
            LaunchedEffect(deleteTarget) {
                vm.deleteTask(deleteTarget!!)
                deleteTarget = null
            }
        } else {
            AlertDialog(
                onDismissRequest = closeDeleteDialog,
                confirmButton = {
                    Button(onClick = {
                        vm.verifyPassword(deletePwd) { ok ->
                            if (ok) vm.deleteTask(deleteTarget!!) else vm.message.value = "密码错误，请重新输入"
                            closeDeleteDialog()
                            deletePwd = ""
                        }
                    }) { Text("删除") }
                },
                dismissButton = { TextButton(onClick = closeDeleteDialog) { Text("取消") } },
                title = { Text("删除任务") },
                text = {
                    OutlinedTextField(
                        value = deletePwd,
                        onValueChange = { deletePwd = it },
                        label = { Text("请输入解锁密码") },
                        visualTransformation = PasswordVisualTransformation()
                    )
                }
            )
        }
    }

    if (firstPromptDialog) {
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {
                Button(onClick = {
                    vm.setSecurityPromptDone(true)
                    closeFirstPromptDialog()
                    vm.message.value = "请在个人中心设置解锁密码并开启加密"
                }) { Text("是") }
            },
            dismissButton = {
                TextButton(onClick = {
                    vm.setSecurityPromptDone(true)
                    closeFirstPromptDialog()
                }) { Text("否") }
            },
            title = { Text("是否开启本地加密解锁") },
            text = { Text("你可以稍后在个人中心随时开启或关闭加密。") }
        )
    }

    if (pageTip != null) {
        AlertDialog(
            onDismissRequest = { pageTip = null },
            confirmButton = {},
            title = { Text(pageTip!!) },
            text = { Text("请稍候...") }
        )
    }

    if (backgroundPopupPromptDialog) {
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {
                Button(onClick = {
                    markBackgroundPopupPromptShown(context)
                    backgroundPopupPromptDialog = false
                    openBackgroundPopupSettings(context)
                }) { Text("去设置") }
            },
            dismissButton = {
                TextButton(onClick = {
                    markBackgroundPopupPromptShown(context)
                    backgroundPopupPromptDialog = false
                }) { Text("稍后") }
            },
            title = { Text("建议开启以下权限，方便接收任务逾期提醒") },
            text = { Text("1. 建议在 应用详情 中允许 TaskGame 的“后台弹出界面”权限并开启“铃声”和“震动”。\n2. 建议在“设置->应用->自启动、关联启动”中开启 TaskGame。") }
        )
    }

}

@Composable
private fun TaskCard(
    model: TaskUiModel,
    expanded: Boolean,
    onToggle: () -> Unit,
    onStart: () -> Unit,
    onComplete: () -> Unit,
    onDelete: () -> Unit,
    onRevive: () -> Unit,
    onSubTaskDone: (Long) -> Unit,
    imminent: Boolean
) {
    val bg = when (model.task.status) {
        TaskStatus.Pending -> Color(0xFFEDEDED)
        TaskStatus.InProgress -> Color(0xFFE8F1FF)
        TaskStatus.Completed -> Color(0xFFE6F7E6)
        TaskStatus.Overdue -> Color(0xFFFFEBEB)
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .then(
                if (imminent) {
                    Modifier.border(width = 1.5.dp, color = Color(0xFFE53935), shape = RoundedCornerShape(12.dp))
                } else {
                    Modifier
                }
            )
            .clickable { onToggle() },
        colors = CardDefaults.cardColors(containerColor = bg)
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(formatDateTime(model.task.deadlineMillis), style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(model.task.name, fontWeight = FontWeight.Bold)
                Text(model.task.difficulty.name)
            }
            Text("优先级：${model.task.priority}")
            Text("状态：${statusLabel(model.task.status)}")
            if (expanded) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text("描述：${model.task.description.ifBlank { "无" }}")
                model.subTasks.forEach { sub ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(sub.name)
                        if (sub.completed) {
                            Text("已完成", color = Color(0xFF2E7D32))
                        } else {
                            Text(
                                "完成",
                                modifier = Modifier.clickable { onSubTaskDone(sub.id) },
                                color = Color(0xFF1565C0)
                            )
                        }
                    }
                }
                model.completionPercent?.let {
                    Text("总任务完成百分比：${String.format(Locale.getDefault(), "%.2f", it)}%")
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row {
                    when (model.task.status) {
                        TaskStatus.Pending -> Button(onClick = onStart) { Text("开启") }
                        TaskStatus.InProgress -> Button(onClick = onComplete) { Text("完成") }
                        TaskStatus.Overdue -> Button(onClick = onRevive) { Text("复活") }
                        TaskStatus.Completed -> {}
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(onClick = onDelete) { Text("删除") }
                }
            }
        }
    }
}

@Composable
private fun CreateTaskScreen(
    onBack: () -> Unit,
    onSubmit: (String, TaskDifficulty, Long, Int, String, List<String>) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var difficulty by remember { mutableStateOf(TaskDifficulty.Low) }
    var deadline by remember { mutableLongStateOf(currentMinuteMillis() + 24 * 60 * 60 * 1000L) }
    var priority by remember { mutableIntStateOf(1) }
    var description by remember { mutableStateOf("") }
    val subTasks = remember { mutableStateListOf<String>() }
    var subTaskInput by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    val calendar = remember { Calendar.getInstance() }
    val context = LocalContext.current

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
    ) {
        TextButton(onClick = onBack) { Text("< 返回") }
        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("任务名称") }, modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(8.dp))
        Row {
            TaskDifficulty.entries.forEach {
                FilterChip(
                    selected = difficulty == it,
                    onClick = { difficulty = it },
                    label = { Text(it.name) },
                    modifier = Modifier.padding(end = 8.dp)
                )
            }
        }
        OutlinedTextField(
            value = formatDateTime(deadline),
            onValueChange = {},
            readOnly = true,
            textStyle = TextStyle(fontSize = 17.sp),
            modifier = Modifier.fillMaxWidth().clickable {
                calendar.timeInMillis = deadline
                DatePickerDialog(
                    context,
                    { _, year, month, dayOfMonth ->
                        TimePickerDialog(
                            context,
                            { _, hour, minute ->
                                calendar.set(year, month, dayOfMonth, hour, minute, 0)
                                calendar.set(Calendar.MILLISECOND, 0)
                                deadline = calendar.timeInMillis - (calendar.timeInMillis % 60000L)
                            },
                            calendar.get(Calendar.HOUR_OF_DAY),
                            calendar.get(Calendar.MINUTE),
                            true
                        ).show()
                    },
                    calendar.get(Calendar.YEAR),
                    calendar.get(Calendar.MONTH),
                    calendar.get(Calendar.DAY_OF_MONTH)
                ).show()
            },
            label = { Text("截止时间（<点击选择）") },
        )
        Spacer(modifier = Modifier.height(18.dp))
        Text("任务优先级（1-100，1最高）")
        AndroidView(
            factory = { context ->
                NumberPicker(context).apply {
                    minValue = 1
                    maxValue = 100
                    value = priority
                    wrapSelectorWheel = true
                    setOnValueChangedListener { _, _, newVal ->
                        priority = newVal
                    }
                }
            },
            update = {
                if (it.value != priority) it.value = priority
            },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text("任务描述（可选）") }, modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(value = subTaskInput, onValueChange = { subTaskInput = it }, label = { Text("子任务名称") }, modifier = Modifier.fillMaxWidth())
        Button(
            onClick = {
                if (subTaskInput.isNotBlank()) {
                    subTasks.add(subTaskInput)
                    subTaskInput = ""
                }
            }
        ) { Text("添加子任务") }
        subTasks.forEachIndexed { index, item ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(item)
                Text("删除", modifier = Modifier.clickable { subTasks.removeAt(index) }, color = Color.Red)
            }
        }
        if (error.isNotBlank()) {
            Text(error, color = Color.Red)
        }
        Spacer(modifier = Modifier.height(12.dp))
        Button(modifier = Modifier.fillMaxWidth(), onClick = {
            when {
                name.isBlank() -> error = "任务名称不能为空"
                priority !in 1..100 -> error = "任务优先级需在1到100之间"
                else -> {
                    runCatching {
                        error = ""
                        onSubmit(name.trim(), difficulty, deadline, priority, description, subTasks.toList())
                    }.onFailure {
                        error = "创建失败，请重试"
                    }
                }
            }
        }) { Text("确认创建") }
    }
}

@Composable
private fun ProfileScreen(
    vm: MainViewModel,
    activity: Activity,
    onBack: () -> Unit
) {
    val dashboard by vm.dashboard.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var username by remember { mutableStateOf(dashboard.settings.username) }
    var pwdDialog by remember { mutableStateOf(false) }
    var oldPwd by remember { mutableStateOf("") }
    var newPwd by remember { mutableStateOf("") }
    var newPwd2 by remember { mutableStateOf("") }
    var initDialog by remember { mutableStateOf(false) }
    var initPwd1 by remember { mutableStateOf("") }
    var initPwd2 by remember { mutableStateOf("") }
    var pageTip by remember { mutableStateOf<String?>(null) }
    val closeInitDialog = {
        if (initDialog) {
            initDialog = false
        }
    }
    val closePwdDialog = {
        if (pwdDialog) {
            pwdDialog = false
        }
    }

    LaunchedEffect(message) {
        if (!message.isNullOrBlank()) {
            pageTip = message
            vm.clearMessage()
        }
    }
    LaunchedEffect(pageTip) {
        if (pageTip != null) {
            delay(1000)
            pageTip = null
        }
    }

    Scaffold { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            TextButton(onClick = onBack) { Text("< 返回首页") }
            OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text("用户名") }, modifier = Modifier.fillMaxWidth())
            Button(onClick = { vm.saveUsername(username) }) { Text("保存") }
            Spacer(modifier = Modifier.height(12.dp))
            Text("当前积分：${dashboard.settings.score}", fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("加密解锁")
                Spacer(modifier = Modifier.weight(1f))
                Switch(
                    checked = dashboard.settings.lockEnabled,
                    onCheckedChange = { enabled ->
                        if (!dashboard.settings.passwordInitialized && enabled) {
                            initDialog = true
                        } else if (dashboard.settings.passwordInitialized) {
                            vm.setLockEnabled(enabled)
                            vm.message.value = if (enabled) "加密已开启" else "加密已关闭"
                        }
                    }
                )
            }
            Button(
                enabled = dashboard.settings.passwordInitialized,
                onClick = { pwdDialog = true }
            ) { Text("修改密码") }
            Spacer(modifier = Modifier.weight(1f))
            Button(onClick = { activity.finishAffinity() }, modifier = Modifier.fillMaxWidth()) { Text("退出软件") }
        }
    }

    if (pageTip != null) {
        AlertDialog(
            onDismissRequest = { pageTip = null },
            confirmButton = {},
            title = { Text(pageTip!!) },
            text = { Text("请稍候...") }
        )
    }

    if (initDialog) {
        AlertDialog(
            onDismissRequest = closeInitDialog,
            confirmButton = {
                Button(onClick = {
                    when {
                        initPwd1.length !in 6..16 -> vm.message.value = "密码长度需为6-16位"
                        initPwd1 != initPwd2 -> vm.message.value = "两次密码不一致"
                        else -> vm.setupPassword(initPwd1) {
                            vm.message.value = if (it) "加密已开启" else "加密初始化失败"
                        }
                    }
                    closeInitDialog()
                }) { Text("确认") }
            },
            dismissButton = { TextButton(onClick = closeInitDialog) { Text("取消") } },
            title = { Text("设置解锁密码") },
            text = {
                Column {
                    OutlinedTextField(value = initPwd1, onValueChange = { initPwd1 = it }, label = { Text("输入密码") }, visualTransformation = PasswordVisualTransformation())
                    OutlinedTextField(value = initPwd2, onValueChange = { initPwd2 = it }, label = { Text("确认密码") }, visualTransformation = PasswordVisualTransformation())
                }
            }
        )
    }

    if (pwdDialog) {
        AlertDialog(
            onDismissRequest = closePwdDialog,
            confirmButton = {
                Button(onClick = {
                    if (newPwd.length !in 6..16 || newPwd != newPwd2) {
                        vm.message.value = "新密码不合法"
                    } else {
                        vm.changePassword(oldPwd, newPwd) { ok ->
                            vm.message.value = if (ok) "密码修改成功" else "密码错误，请重新输入"
                        }
                    }
                    closePwdDialog()
                }) { Text("确认") }
            },
            dismissButton = { TextButton(onClick = closePwdDialog) { Text("取消") } },
            title = { Text("修改密码") },
            text = {
                Column {
                    OutlinedTextField(value = oldPwd, onValueChange = { oldPwd = it }, label = { Text("原密码") }, visualTransformation = PasswordVisualTransformation())
                    OutlinedTextField(value = newPwd, onValueChange = { newPwd = it }, label = { Text("新密码") }, visualTransformation = PasswordVisualTransformation())
                    OutlinedTextField(value = newPwd2, onValueChange = { newPwd2 = it }, label = { Text("确认新密码") }, visualTransformation = PasswordVisualTransformation())
                }
            }
        )
    }
}

@Composable
private fun ReviveDialog(vm: MainViewModel, taskId: Long, onDone: () -> Unit) {
    val dashboard by vm.dashboard.collectAsStateWithLifecycle()
    val task = dashboard.tasks.firstOrNull { it.task.id == taskId }?.task
    if (task == null) {
        LaunchedEffect(Unit) { onDone() }
        return
    }
    var nDays by remember { mutableStateOf("1") }
    val n = nDays.toIntOrNull() ?: 0
    val cost = task.difficulty.reward + n
    val enough = dashboard.settings.score >= cost && n in 1..365
    AlertDialog(
        onDismissRequest = onDone,
        confirmButton = {
            Button(enabled = enough, onClick = {
                vm.reviveTask(taskId, n)
                onDone()
            }) { Text("确认复活") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("取消") } },
        title = { Text("任务复活") },
        text = {
            Column {
                Text("任务：${task.name}")
                Text("基础积分：${task.difficulty.reward}")
                OutlinedTextField(
                    value = nDays,
                    onValueChange = { nDays = it.filter { ch -> ch.isDigit() } },
                    label = { Text("请输入顺延天数n") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                Text("复活消耗 = 基础积分 + n = $cost")
                if (!enough) Text("积分不足，无法复活", color = Color.Red)
            }
        }
    )
}

private fun statusLabel(status: TaskStatus): String = when (status) {
    TaskStatus.Pending -> "待开启"
    TaskStatus.InProgress -> "进行中"
    TaskStatus.Completed -> "已完成"
    TaskStatus.Overdue -> "已逾期"
}

private const val TASKGAME_PREFS = "taskgame_local_prefs"
private const val KEY_BG_POPUP_PROMPT_SHOWN = "bg_popup_prompt_shown"

private fun shouldShowBackgroundPopupPrompt(context: Context): Boolean {
    val prefs = context.getSharedPreferences(TASKGAME_PREFS, Context.MODE_PRIVATE)
    return !prefs.getBoolean(KEY_BG_POPUP_PROMPT_SHOWN, false)
}

private fun markBackgroundPopupPromptShown(context: Context) {
    val prefs: SharedPreferences = context.getSharedPreferences(TASKGAME_PREFS, Context.MODE_PRIVATE)
    prefs.edit { putBoolean(KEY_BG_POPUP_PROMPT_SHOWN, true) }
}

private fun openBackgroundPopupSettings(context: Context) {
    val intents = listOf(
        Intent().apply {
            setClassName(
                "com.coloros.safecenter",
                "com.coloros.safecenter.permission.PermissionAppAllPermissionActivity"
            )
            putExtra("packageName", context.packageName)
        },
        Intent().apply {
            setClassName(
                "com.oplus.safecenter",
                "com.oplus.safecenter.permission.PermissionAppAllPermissionActivity"
            )
            putExtra("packageName", context.packageName)
        },
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = "package:${context.packageName}".toUri()
        }
    )
    intents.firstOrNull { intent ->
        runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }
}
