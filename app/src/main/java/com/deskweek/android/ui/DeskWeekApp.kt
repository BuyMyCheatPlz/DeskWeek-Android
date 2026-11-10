package com.deskweek.android.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.deskweek.android.core.data.TaskRepository
import com.deskweek.android.core.model.Task
import com.deskweek.android.core.model.TaskRules
import com.deskweek.android.wallpaper.DeskWeekWallpaperService
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

private val WEEKDAY_CN = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

private fun Task.colorArgb(): Color = runCatching {
    Color(android.graphics.Color.parseColor(this.color))
}.getOrDefault(Color(0xFF9BD3C7))

@Composable
fun DeskWeekApp() {
    val state by TaskRepository.state.collectAsState()
    val context = LocalContext.current
    val width = LocalConfiguration.current.screenWidthDp
    val wide = width >= 840

    var tab by remember { mutableStateOf(0) }
    var selectedDate by remember { mutableStateOf(LocalDate.now()) }
    var showAdd by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Task?>(null) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Text("周") },
                    label = { Text("周历") },
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Text("单") },
                    label = { Text("清单") },
                )
                NavigationBarItem(
                    selected = tab == 2,
                    onClick = { tab = 2 },
                    icon = { Text("设") },
                    label = { Text("设置") },
                )
            }
        },
        floatingActionButton = {
            if (tab != 2) {
                FloatingActionButton(onClick = { showAdd = true }) { Text("+") }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                0 -> WeekScreen(
                    selectedDate = selectedDate,
                    wide = wide,
                    onSelect = { selectedDate = it },
                    onEdit = { editing = it },
                )
                1 -> TaskListScreen(onEdit = { editing = it })
                else -> SettingsScreen(
                    onOpenWallpaper = { context.startActivity(DeskWeekWallpaperService.changeWallpaperIntent(context)) },
                )
            }
        }
    }

    if (showAdd) {
        AddTaskDialog(
            initialDate = selectedDate,
            onDismiss = { showAdd = false },
            onConfirm = { title, date, time ->
                TaskRepository.add(title = title, date = date.toString(), time = time)
                showAdd = false
                selectedDate = date
            },
        )
    }

    editing?.let { task ->
        EditTaskDialog(
            task = task,
            onDismiss = { editing = null },
            onSave = {
                TaskRepository.update(it)
                editing = null
            },
        )
    }
}

@Composable
private fun WeekScreen(
    selectedDate: LocalDate,
    wide: Boolean,
    onSelect: (LocalDate) -> Unit,
    onEdit: (Task) -> Unit,
) {
    val monday = selectedDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val days = (0..6).map { monday.plusDays(it.toLong()) }
    val today = LocalDate.now()

    if (wide) {
        // 平板/展开态：左侧整周导航 + 右侧当日详情
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(Modifier.weight(0.42f)) {
                WeekHeader(monday)
                Spacer(Modifier.height(8.dp))
                days.forEachIndexed { index, day ->
                    DayRow(
                        day = day,
                        label = WEEKDAY_CN[index],
                        selected = day == selectedDate,
                        isToday = day == today,
                        count = TaskRepository.on(day.toString()).count { !it.done },
                        onClick = { onSelect(day) },
                    )
                }
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
            Column(Modifier.weight(0.58f)) {
                DayTasks(selectedDate, onEdit)
            }
        }
    } else {
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
            WeekHeader(monday)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                days.forEachIndexed { index, day ->
                    DayCell(
                        modifier = Modifier.weight(1f),
                        day = day,
                        label = WEEKDAY_CN[index],
                        selected = day == selectedDate,
                        isToday = day == today,
                        count = TaskRepository.on(day.toString()).count { !it.done },
                        onClick = { onSelect(day) },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            DayTasks(selectedDate, onEdit)
        }
    }
}

@Composable
private fun WeekHeader(monday: LocalDate) {
    Text(
        text = "${monday.year} 年 ${monday.monthValue} 月 · 第 ${weekOfYear(monday)} 周",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun DayRow(
    day: LocalDate,
    label: String,
    selected: Boolean,
    isToday: Boolean,
    count: Int,
    onClick: () -> Unit,
) {
    val bg = when {
        selected -> MaterialTheme.colorScheme.primaryContainer
        isToday -> MaterialTheme.colorScheme.secondaryContainer
        else -> Color.Transparent
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(44.dp))
        Text(
            text = "${day.dayOfMonth} 日",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (selected || isToday) FontWeight.Bold else FontWeight.Normal,
        )
        Spacer(Modifier.weight(1f))
        if (count > 0) {
            Text(
                text = "$count 项",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun DayTasks(selectedDate: LocalDate, onEdit: (Task) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Text(
            text = "${selectedDate.monthValue} 月 ${selectedDate.dayOfMonth} 日 · 待办",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        val tasks = TaskRepository.on(selectedDate.toString())
        if (tasks.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                Text("这一天还没有安排", color = MaterialTheme.colorScheme.outline)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(tasks, key = { it.id }) { task ->
                    TaskRow(task = task, onEdit = { onEdit(task) })
                }
            }
        }
    }
}
@Composable
private fun DayCell(
    modifier: Modifier,
    day: LocalDate,
    label: String,
    selected: Boolean,
    isToday: Boolean,
    count: Int,
    onClick: () -> Unit,
) {
    val bg = when {
        selected -> MaterialTheme.colorScheme.primaryContainer
        isToday -> MaterialTheme.colorScheme.secondaryContainer
        else -> Color.Transparent
    }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall)
        Text(
            text = day.dayOfMonth.toString(),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = if (selected || isToday) FontWeight.Bold else FontWeight.Normal,
        )
        if (count > 0) {
            Box(Modifier.padding(top = 2.dp).size(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
        } else {
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun TaskRow(task: Task, onEdit: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onEdit),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = task.done, onCheckedChange = { TaskRepository.toggleDone(task.id) })
            Box(Modifier.size(8.dp).clip(CircleShape).background(task.colorArgb()))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = task.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textDecoration = if (task.done) TextDecoration.LineThrough else null,
                )
                if (task.time.isNotBlank() || task.defer != "none") {
                    Text(
                        text = listOfNotNull(
                            task.time.takeIf { it.isNotBlank() },
                            TaskRules.deferLabel(task).takeIf { task.defer != "none" },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
    }
}

@Composable
private fun TaskListScreen(onEdit: (Task) -> Unit) {
    var filter by remember { mutableStateOf("todo") }
    val today = LocalDate.now()
    val state by TaskRepository.state.collectAsState()
    val all = state.items
    val list = when (filter) {
        "done" -> TaskRules.sortCompleted(all.filter { !it.deleted && it.done })
        "trash" -> all.filter { it.deleted }
        else -> TaskRules.sortTasks(all.filter { !it.deleted && !it.done }, today)
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("todo" to "未完成", "done" to "已完成", "trash" to "垃圾箱").forEach { (key, label) ->
                FilterChip(selected = filter == key, onClick = { filter = key }, label = { Text(label) })
            }
        }
        Spacer(Modifier.height(8.dp))
        if (list.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                Text("这里是空的", color = MaterialTheme.colorScheme.outline)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(list, key = { it.id }) { task ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { TaskRow(task = task, onEdit = { onEdit(task) }) }
                        TextButton(onClick = {
                            when (filter) {
                                "trash" -> TaskRepository.purge(task.id)
                                else -> TaskRepository.softDelete(task.id)
                            }
                        }) { Text(if (filter == "trash") "彻底删除" else "删除") }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(onOpenWallpaper: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences(DeskWeekWallpaperService.PREFS, android.content.Context.MODE_PRIVATE)
    }
    var dark by remember { mutableStateOf(prefs.getBoolean(DeskWeekWallpaperService.KEY_DARK, false)) }
    var status by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("外观", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column { Text("壁纸深色模式"); Text("仅影响壁纸配色，不改系统主题", style = MaterialTheme.typography.labelSmall) }
            Switch(checked = dark, onCheckedChange = {
                dark = it
                prefs.edit().putBoolean(DeskWeekWallpaperService.KEY_DARK, it).apply()
            })
        }

        HorizontalDivider()
        Text("显示形态", style = MaterialTheme.typography.titleMedium)
        Button(onClick = {
            val mgr = android.appwidget.AppWidgetManager.getInstance(context)
            if (mgr.isRequestPinAppWidgetSupported) {
                val provider = android.content.ComponentName(context, com.deskweek.android.widget.DeskWeekWidgetReceiver::class.java)
                mgr.requestPinAppWidget(provider, null, null)
            } else {
                status = "当前桌面不支持一键添加，请长按桌面 → 小组件 → 找 DeskWeek"
            }
        }) { Text("添加桌面小组件") }
        Text(
            "小组件透明背景，保留你的壁纸，可与 Wallpaper Engine 等动态壁纸共存。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
        OutlinedButton(onClick = onOpenWallpaper) { Text("动态壁纸（实验，非首版形态）") }
        Text(
            "系统同一时间只能运行一个动态壁纸；启用 DeskWeek 壁纸会顶替当前的视频壁纸。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )

        HorizontalDivider()
        Text("数据", style = MaterialTheme.typography.titleMedium)
        Text(
            "数据文件：${TaskRepository.storeFile().absolutePath}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )

        // 导出：直接把 data.json 写到用户选的位置，桌面版可原样读取
        val exportLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/json"),
        ) { uri ->
            if (uri != null) {
                status = runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        val tmp = java.io.File(context.cacheDir, "export.json")
                        TaskRepository.exportTo(tmp)
                        tmp.inputStream().use { it.copyTo(out) }
                    }
                    "已导出 data.json"
                }.getOrElse { "导出失败：${it.message}" }
            }
        }

        // 导入：读用户选的 data.json，校验后整体替换
        val importLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { uri ->
            if (uri != null) {
                status = runCatching {
                    val tmp = java.io.File(context.cacheDir, "import.json")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        tmp.outputStream().use { input.copyTo(it) }
                    }
                    TaskRepository.importFrom(tmp)
                    "已导入 ${TaskRepository.state.value.items.size} 条"
                }.getOrElse { "导入失败（文件格式不对？）：${it.message}" }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { exportLauncher.launch("deskweek-data.json") }) { Text("导出 data.json") }
            OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }) {
                Text("导入 data.json")
            }
        }
        Text(
            "导出的文件可直接替换桌面版 data/data.json，反之亦然。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
        if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.labelSmall)

        HorizontalDivider()
        Text("同步", style = MaterialTheme.typography.titleMedium)
        val syncDir = remember { java.io.File(context.filesDir, "sync") }
        val syncStore = remember { com.deskweek.android.core.sync.SyncStore(syncDir) }
        val syncIdentity = remember { com.deskweek.android.core.sync.SyncIdentity.loadOrCreate(syncDir) }
        val syncPrefs = remember { context.getSharedPreferences("deskweek_sync", android.content.Context.MODE_PRIVATE) }
        var host by remember {
            mutableStateOf(
                syncPrefs.getString("host", com.deskweek.android.core.sync.SyncSession.EMULATOR_HOST) ?: "10.0.2.2",
            )
        }
        var portText by remember { mutableStateOf(syncPrefs.getInt("port", com.deskweek.android.core.sync.SyncSession.P0_PORT).toString()) }
        val scope = rememberCoroutineScope()

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = host,
                onValueChange = {
                    host = it
                    syncPrefs.edit().putString("host", it).apply()
                },
                label = { Text("主机") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = portText,
                onValueChange = {
                    portText = it.filter(Char::isDigit)
                    portText.toIntOrNull()?.let { port -> syncPrefs.edit().putInt("port", port).apply() }
                },
                label = { Text("端口") },
                singleLine = true,
                modifier = Modifier.width(110.dp),
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                status = "写入本地操作…"
                scope.launch {
                    status = withContext(Dispatchers.IO) {
                        runCatching {
                            val op = syncStore.log.nextOp(
                                actorId = syncIdentity.actorId,
                                taskId = java.util.UUID.randomUUID().toString(),
                                kind = "create",
                                changes = kotlinx.serialization.json.buildJsonObject {
                                    put("title", JsonPrimitive("本机操作 ${syncStore.allOps().size + 1}"))
                                    put("date", JsonPrimitive(LocalDate.now().toString()))
                                    put("createdAt", JsonPrimitive(java.time.Instant.now().toString()))
                                },
                                recordedAt = java.time.Instant.now().toString(),
                            )
                            syncStore.receive(listOf(syncIdentity.signed(op)))
                            "已写入本地操作，共 ${syncStore.allOps().size} 条"
                        }.getOrElse { "写入失败：${it.message}" }
                    }
                }
            }) { Text("写入本地操作") }

            OutlinedButton(onClick = {
                status = "同步中…"
                scope.launch {
                    status = withContext(Dispatchers.IO) {
                        val port = portText.toIntOrNull() ?: com.deskweek.android.core.sync.SyncSession.P0_PORT
                        var lastError: String? = null
                        var result: String? = null
                        // 最多重试 3 次，退避 400ms/800ms；失败保留原因
                        for (attempt in 1..3) {
                            val outcome = runCatching {
                                com.deskweek.android.core.sync.SyncSession(
                                    host = host,
                                    port = port,
                                    expectedSha256 = com.deskweek.android.core.sync.SyncSession.P0_PIN,
                                    identity = syncIdentity,
                                    store = syncStore,
                                ).run()
                            }
                            if (outcome.isSuccess) {
                                val r = outcome.getOrThrow()
                                result = "同步完成 · 第 $attempt 次 · ${r.tlsVersion} · 拉取 ${r.pulled} 条 · " +
                                    "推送 ${r.pushed} 条 · 向量 ${r.vectors}"
                                break
                            }
                            lastError = outcome.exceptionOrNull()?.message
                            if (attempt < 3) Thread.sleep(400L * attempt)
                        }
                        result ?: "同步失败（已重试 3 次）：$lastError"
                    }
                }
            }) { Text("立即同步") }
        }
        Text("需宿主机运行 node scripts/serve-p0.cjs；验证 TLS + 帧 + ECDSA 签名 + 缺口补齐。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun AddTaskDialog(initialDate: LocalDate, onDismiss: () -> Unit, onConfirm: (String, LocalDate, String) -> Unit) {
    var title by remember { mutableStateOf("") }
    var dateText by remember { mutableStateOf(initialDate.toString()) }
    var time by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("记一件事") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("标题") }, singleLine = true)
                OutlinedTextField(value = dateText, onValueChange = { dateText = it }, label = { Text("日期 YYYY-MM-DD") }, singleLine = true)
                OutlinedTextField(value = time, onValueChange = { time = it }, label = { Text("时间 HH:mm（可空）") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(
                enabled = title.isNotBlank() && TaskRules.isValidDateKey(dateText),
                onClick = { onConfirm(title, LocalDate.parse(dateText), time) },
            ) { Text("添加") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun EditTaskDialog(task: Task, onDismiss: () -> Unit, onSave: (Task) -> Unit) {
    var title by remember { mutableStateOf(task.title) }
    var detail by remember { mutableStateOf(task.detail) }
    var dateText by remember { mutableStateOf(task.date) }
    var time by remember { mutableStateOf(task.time) }
    var defer by remember { mutableStateOf(TaskRules.normalizeDefer(task.defer)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("修改事项") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("标题") }, singleLine = true)
                OutlinedTextField(value = detail, onValueChange = { detail = it }, label = { Text("备忘") }, minLines = 2)
                OutlinedTextField(value = dateText, onValueChange = { dateText = it }, label = { Text("日期") }, singleLine = true)
                OutlinedTextField(value = time, onValueChange = { time = it }, label = { Text("时间") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("none" to "立即", "1d" to "提前1天", "3d" to "提前3天", "1m" to "提前1月").forEach { (key, label) ->
                        FilterChip(selected = defer == key, onClick = { defer = key }, label = { Text(label) })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = title.isNotBlank(),
                onClick = {
                    onSave(
                        task.copy(
                            title = title.trim().take(100),
                            detail = detail.take(4000),
                            date = if (TaskRules.isValidDateKey(dateText)) dateText else "",
                            time = if (TaskRules.isValidTime(time)) time else "",
                            defer = TaskRules.normalizeDefer(defer),
                        ),
                    )
                },
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private fun weekOfYear(date: LocalDate): Int =
    date.get(java.time.temporal.WeekFields.ISO.weekOfWeekBasedYear())
