package com.deskweek.android.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.IntOffset
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
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.glance.appwidget.updateAll
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

private fun changeSafely(action: () -> Unit): Boolean = try {
    action(); TaskRepository.clearError(); true
} catch (error: Exception) { TaskRepository.reportError(error); false }

private fun Task.colorArgb(): Color = runCatching {
    Color(android.graphics.Color.parseColor(this.color))
}.getOrDefault(Color(0xFF9BD3C7))

@Composable
fun DeskWeekApp() {
    val state by TaskRepository.state.collectAsState()
    val context = LocalContext.current
    val width = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp().value }
    val wide = width >= 840

    var tab by rememberSaveable { mutableStateOf(0) }
    var selectedDateText by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    val selectedDate = LocalDate.parse(selectedDateText)
    var showAdd by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Task?>(null) }

    val saveError by TaskRepository.error.collectAsState()
    Scaffold(
        topBar = { saveError?.let { message ->
            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).padding(8.dp)) {
                Text(message, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer)
                TextButton(onClick = { TaskRepository.clearError() }) { Text("关闭") }
            }
        } },
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
                    onSelect = { selectedDateText = it.toString() },
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
                if (changeSafely { TaskRepository.add(title = title, date = date, time = time) }) {
                    showAdd = false
                    if (TaskRules.isValidDateKey(date)) selectedDateText = date
                }
            },
        )
    }

    editing?.let { task ->
        EditTaskDialog(
            task = task,
            onDismiss = { editing = null },
            onSave = {
                if (changeSafely { TaskRepository.update(it, before = task) }) editing = null
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
    val data by TaskRepository.state.collectAsState()
    val monday = selectedDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val days = (0..6).map { monday.plusDays(it.toLong()) }
    val today = LocalDate.now()
    var moving by remember { mutableStateOf<Task?>(null) }
    var dragTask by remember { mutableStateOf<Task?>(null) }
    var dragPos by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    val dayBounds = remember(monday, wide) { androidx.compose.runtime.mutableStateMapOf<String, androidx.compose.ui.geometry.Rect>() }

    fun dropAt(position: androidx.compose.ui.geometry.Offset) {
        val task = dragTask ?: return
        val target = dayBounds.entries.firstOrNull { it.value.contains(position) }?.key ?: return

        if (target != task.date) changeSafely { TaskRepository.update(task.copy(date = target), before = task) }
    }

    moving?.let { task ->
        MoveTaskDialog(
            task = task,
            weekStart = monday,
            onDismiss = { moving = null },
            onPick = { date ->
                changeSafely { TaskRepository.update(task.copy(date = date, time = if (date.isBlank()) "" else task.time, defer = if (date.isBlank()) "none" else task.defer), before = task) }
                moving = null
            },
        )
    }

    Box(Modifier.fillMaxSize()) {
    if (wide) {
        // 平板/展开态：左侧整周导航 + 右侧当日详情
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(Modifier.weight(0.42f)) {
                WeekHeader(monday, onSelect)
                Spacer(Modifier.height(8.dp))
                days.forEachIndexed { index, day ->
                    DayRow(
                        modifier = Modifier.testTag("day-$day").onGloballyPositioned {
                            dayBounds[day.toString()] = it.boundsInRoot()
                        },
                        day = day,
                        label = WEEKDAY_CN[index],
                        selected = day == selectedDate,
                        isToday = day == today,
                        count = data.items.count { !it.deleted && !it.done && it.date == day.toString() },
                        onClick = { onSelect(day) },
                    )
                }
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
            Column(Modifier.weight(0.58f)) {
                DayTasks(
                    selectedDate, onEdit,
                    onPickDate = { moving = it },
                    onDragStart = { t -> dragTask = t },
                    onDrag = { pos -> dragPos = pos },
                    onDragEnd = { dropAt(dragPos); dragTask = null },
                    onDragCancel = { dragTask = null },
                )
            }
        }
    } else {
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
            WeekHeader(monday, onSelect)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                days.forEachIndexed { index, day ->
                    DayCell(
                        modifier = Modifier.weight(1f).testTag("day-$day").onGloballyPositioned {
                            dayBounds[day.toString()] = it.boundsInRoot()
                        },
                        day = day,
                        label = WEEKDAY_CN[index],
                        selected = day == selectedDate,
                        isToday = day == today,
                        count = data.items.count { !it.deleted && !it.done && it.date == day.toString() },
                        onClick = { onSelect(day) },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            DayTasks(
                selectedDate, onEdit,
                onPickDate = { moving = it },
                onDragStart = { t -> dragTask = t },
                onDrag = { pos -> dragPos = pos },
                onDragEnd = { dropAt(dragPos); dragTask = null },
                onDragCancel = { dragTask = null },
            )
        }
    }

        // 拖拽中的悬浮标签
        dragTask?.let { task ->
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(8.dp),
                shadowElevation = 6.dp,
                modifier = Modifier
                    .offset { IntOffset(dragPos.x.toInt() - 40, dragPos.y.toInt() - 24) }
                    .padding(8.dp),
            ) {
                Text(task.title.take(10), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun WeekHeader(monday: LocalDate, onSelect: (LocalDate) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(onClick = { onSelect(monday.minusWeeks(1)) }) { Text("上一周") }
        TextButton(onClick = { onSelect(LocalDate.now()) }) { Text("今天") }
        TextButton(onClick = { onSelect(monday.plusWeeks(1)) }) { Text("下一周") }
    }
    Text(
        text = "${monday.year} 年 ${monday.monthValue} 月 · 第 ${weekOfYear(monday)} 周",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun DayRow(
    modifier: Modifier = Modifier,
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
        modifier = modifier
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
        Text(
            text = com.deskweek.android.core.calendar.LunarProvider.ofCached(day).shortLabel,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(start = 8.dp),
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
private fun DayTasks(
    selectedDate: LocalDate,
    onEdit: (Task) -> Unit,
    onPickDate: (Task) -> Unit,
    onDragStart: (Task) -> Unit,
    onDrag: (androidx.compose.ui.geometry.Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
) {
    val data by TaskRepository.state.collectAsState()
    Column(Modifier.fillMaxSize()) {
        Text(
            text = "${selectedDate.monthValue} 月 ${selectedDate.dayOfMonth} 日 · " +
                com.deskweek.android.core.calendar.LunarProvider.ofCached(selectedDate).let { info ->
                    if (info.jieQi.isNotEmpty()) info.jieQi else "${info.monthCn}月${info.dayCn}"
                } + " · 待办",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        val tasks = TaskRules.sortTasks(data.items.filter { !it.deleted && it.date == selectedDate.toString() }, LocalDate.now())
        if (tasks.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                Text("这一天还没有安排", color = MaterialTheme.colorScheme.outline)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(tasks, key = { it.id }) { task ->
                    TaskRow(
                        task = task,
                        onEdit = { onEdit(task) },
                        onMove = { onPickDate(task) },
                        onDragStart = { onDragStart(task) },
                        onDrag = onDrag,
                        onDragEnd = onDragEnd,
                        onDragCancel = onDragCancel,
                    )
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
        Text(
            text = com.deskweek.android.core.calendar.LunarProvider.ofCached(day).shortLabel,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        )
        if (count > 0) {
            Box(Modifier.padding(top = 2.dp).size(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
        } else {
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun TaskRow(
    task: Task,
    onEdit: () -> Unit,
    onMove: (() -> Unit)? = null,
    onDragStart: () -> Unit = {},
    onDrag: (androidx.compose.ui.geometry.Offset) -> Unit = {},
    onDragEnd: () -> Unit = {},
    onDragCancel: () -> Unit = {},
) {
    var origin by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned {
                origin = it.boundsInRoot().topLeft
            }
            .pointerInput(task) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { position -> onDragStart(); onDrag(origin + position) },
                    onDrag = { change, _ ->
                        onDrag(origin + change.position)
                        change.consume()
                    },
                    onDragEnd = { onDragEnd() },
                    onDragCancel = { onDragCancel() },
                )
            }
            .clickable(onClick = onEdit),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = task.done, onCheckedChange = { changeSafely { TaskRepository.toggleDone(task.id) } })
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
            if (onMove != null) TextButton(onClick = onMove) { Text("改期") }
        }
    }
}

@Composable
private fun TaskListScreen(onEdit: (Task) -> Unit) {
    var filter by remember { mutableStateOf("todo") }
    var pendingPurge by remember { mutableStateOf<Task?>(null) }
    val today = LocalDate.now()
    val state by TaskRepository.state.collectAsState()
    val all = state.items
    val list = when (filter) {
        "done" -> TaskRules.sortCompleted(all.filter { !it.deleted && it.done })
        "trash" -> all.filter { it.deleted }
        "deferred" -> TaskRules.sortTasks(all.filter { !it.deleted && !it.done && TaskRules.isDeferred(it, today) }, today)
        else -> TaskRules.sortTasks(all.filter { !it.deleted && !it.done && TaskRules.isVisible(it, today) }, today)
    }

    pendingPurge?.let { task -> AlertDialog(
        onDismissRequest = { pendingPurge = null },
        title = { Text("彻底删除这条任务？") }, text = { Text(task.title) },
        confirmButton = { TextButton(onClick = { changeSafely { TaskRepository.purge(task.id) }; pendingPurge = null }) { Text("确认删除") } },
        dismissButton = { TextButton(onClick = { pendingPurge = null }) { Text("取消") } },
    ) }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("todo" to "未完成", "done" to "已完成", "trash" to "垃圾箱", "deferred" to "暂缓").forEach { (key, label) ->
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
                        if (filter == "trash") TextButton(onClick = { changeSafely { TaskRepository.restore(task.id) } }) { Text("恢复") }
                        TextButton(onClick = {
                            when (filter) {
                                "trash" -> pendingPurge = task
                                else -> changeSafely { TaskRepository.softDelete(task.id) }
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
    var pendingImport by remember { mutableStateOf<java.io.File?>(null) }
    var importCount by remember { mutableStateOf(0) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("外观", style = MaterialTheme.typography.titleMedium)
        val widgetPrefs = remember { context.getSharedPreferences("deskweek_widget", android.content.Context.MODE_PRIVATE) }
        var transparent by remember { mutableStateOf(widgetPrefs.getBoolean("transparent", false)) }
        var lightWidgetText by remember { mutableStateOf(widgetPrefs.getBoolean("lightText", true)) }
        val settingsScope = rememberCoroutineScope()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("小组件透明背景")
            Switch(checked = transparent, onCheckedChange = {
                transparent = it
                widgetPrefs.edit().putBoolean("transparent", it).apply()
                settingsScope.launch { com.deskweek.android.widget.DeskWeekWidget().updateAll(context) }
            })
        }
        if (transparent) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("小组件使用浅色文字")
            Switch(checked = lightWidgetText, onCheckedChange = {
                lightWidgetText = it
                widgetPrefs.edit().putBoolean("lightText", it).apply()
                settingsScope.launch { com.deskweek.android.widget.DeskWeekWidget().updateAll(context) }
            })
        }
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
                    val output = context.contentResolver.openOutputStream(uri) ?: error("无法打开导出文件")
                    output.use { out ->
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
                    val source = context.contentResolver.openInputStream(uri) ?: error("无法读取导入文件")
                    source.use { input ->
                        tmp.outputStream().use { input.copyTo(it) }
                    }
                    importCount = com.deskweek.android.core.data.TaskStore(java.io.File(context.filesDir, "data")).parse(tmp.readText()).items.size
                    pendingImport = tmp
                    "已读取 $importCount 条，请选择合并或替换"
                }.getOrElse { "导入失败（文件格式不对？）：${it.message}" }
            }
        }

        pendingImport?.let { file -> AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text("导入 $importCount 条任务") },
            text = { Text("合并保留现有任务；替换会覆盖当前任务列表。操作前保留上一版有效备份。") },
            confirmButton = { TextButton(onClick = {
                if (changeSafely { TaskRepository.importFrom(file, merge = true) }) { status = "导入合并完成"; pendingImport = null }
            }) { Text("合并") } },
            dismissButton = { Row {
                TextButton(onClick = { if (changeSafely { TaskRepository.importFrom(file) }) { status = "导入替换完成"; pendingImport = null } }) { Text("替换") }
                TextButton(onClick = { pendingImport = null }) { Text("取消") }
            } },
        ) }

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
        AiSection()

        HorizontalDivider()
        Text("同步协议实验", style = MaterialTheme.typography.titleMedium)
        Text("目前只交换独立测试日志，尚未接入日常任务，也不能直接在两台 Android 之间同步。", color = MaterialTheme.colorScheme.error)
        val syncDir = remember { java.io.File(context.filesDir, "sync") }
        val syncStoreResult = remember { runCatching { com.deskweek.android.core.sync.SyncStore(syncDir) } }
        if (syncStoreResult.isFailure) {
            Text("同步历史无法恢复，已停止实验同步；本地任务不受影响。", color = MaterialTheme.colorScheme.error)
            return@Column
        }
        val syncStore = syncStoreResult.getOrThrow()
        val syncIdentity = remember { com.deskweek.android.core.sync.SyncIdentity.loadOrCreate(syncDir) }
        val syncPrefs = remember { context.getSharedPreferences("deskweek_sync", android.content.Context.MODE_PRIVATE) }
        var host by remember {
            mutableStateOf(
                syncPrefs.getString("host", "").orEmpty(),
            )
        }
        var portText by remember { mutableStateOf(syncPrefs.getInt("port", com.deskweek.android.core.sync.SyncSession.P0_PORT).toString()) }
        var certificatePin by remember { mutableStateOf(syncPrefs.getString("pin", "").orEmpty()) }
        var syncBusy by remember { mutableStateOf(false) }
        var backgroundSync by remember { mutableStateOf(syncPrefs.getBoolean("enabled", false)) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("后台补齐实验日志", Modifier.weight(1f))
            Switch(backgroundSync, onCheckedChange = {
                backgroundSync = it
                syncPrefs.edit().putBoolean("enabled", it).apply()
                com.deskweek.android.core.sync.SyncWorker.ensureScheduled(context)
            })
        }
        OutlinedTextField(host, { host = it }, label = { Text("已验证设备的局域网地址") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(portText, { portText = it }, label = { Text("端口") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(certificatePin, { certificatePin = it }, label = { Text("证书 SHA-256 指纹") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        val scope = rememberCoroutineScope()
        val trustStore = remember { com.deskweek.android.core.sync.TrustStore(syncDir) }
        val discovery = remember { com.deskweek.android.core.sync.NsdDiscovery(context) }
        androidx.compose.runtime.DisposableEffect(Unit) {
            onDispose { runCatching { discovery.stop() } }
        }
        var pendingPeer by remember { mutableStateOf<com.deskweek.android.core.sync.SyncSession.UntrustedPeerException?>(null) }

        // 一次同步尝试：confirmed 为已核对过的短码，null 表示还没配对
        val runSync: (String?) -> Unit = { confirmed ->
            val endpointHost = host.trim()
            val endpointPort = portText.toIntOrNull()
            val pin = certificatePin.trim()
            if (endpointHost.isBlank() || endpointPort == null || endpointPort !in 1..65535 || !pin.matches(Regex("[0-9a-fA-F]{64}"))) {
                status = "请填写有效地址、端口和 64 位证书指纹"
            } else if (!syncBusy) {
            syncBusy = true
            syncPrefs.edit().putString("host", endpointHost).putInt("port", endpointPort).putString("pin", pin).apply()
            status = "同步中…"
            pendingPeer = null
            scope.launch {
                status = withContext(Dispatchers.IO) {
                    var lastError: String? = null
                    var result: String? = null
                    for (attempt in 1..3) {
                        val outcome = runCatching {
                            com.deskweek.android.core.sync.SyncSession(
                                host = endpointHost,
                                port = endpointPort,
                                expectedSha256 = pin,
                                identity = syncIdentity,
                                store = syncStore,
                                trust = trustStore,
                                confirmedSas = confirmed,
                            ).run()
                        }
                        val failure = outcome.exceptionOrNull()
                        if (outcome.isSuccess) {
                            val r = outcome.getOrThrow()
                            result = "实验日志交换完成 · 第 $attempt 次 · ${r.tlsVersion} · 拉取 ${r.pulled} 条 · " +
                                "推送 ${r.pushed} 条 · 向量 ${r.vectors}"
                            break
                        }
                        if (failure is com.deskweek.android.core.sync.SyncSession.UntrustedPeerException) {
                            pendingPeer = failure
                            return@withContext "未配对设备 · 请核对验证码 ${failure.sas}，确认后再次同步"
                        }
                        lastError = failure?.message
                        if (attempt < 3) Thread.sleep(400L * attempt)
                    }
                    result ?: "同步失败（已重试 3 次）：$lastError"
                }
                syncBusy = false
            }
            }
        }

        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
            }, enabled = !syncBusy) { Text("生成测试操作") }

            OutlinedButton(onClick = { runSync(null) }, enabled = !syncBusy) { Text("测试协议连接") }

            OutlinedButton(onClick = {
                status = "搜索局域网…"
                discovery.start(object : com.deskweek.android.core.sync.NsdDiscovery.Listener {
                    override fun onFound(foundHost: String, foundPort: Int, name: String, groupId: String?) {
                        scope.launch {
                            if (groupId != null && groupId != syncStore.groupId) { status = "发现其他数据集设备，未选中"; return@launch }
                            host = foundHost
                            portText = foundPort.toString()
                            syncPrefs.edit().putString("host", foundHost).putInt("port", foundPort).apply()
                            status = "发现 $name → $foundHost:$foundPort"
                        }
                    }

                    override fun onLost(name: String) {
                        scope.launch { status = "设备下线：$name" }
                    }

                    override fun onError(message: String) {
                        scope.launch { status = message }
                    }
                })
            }) { Text("搜索设备") }

            pendingPeer?.let { peer ->
                OutlinedButton(onClick = { runSync(peer.sas) }) { Text("确认配对 ${peer.sas}") }
            }
        }
        Text("需对端提供兼容实验协议的服务与可信证书指纹；当前桌面工程尚未接入同步。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun MoveTaskDialog(
    task: Task,
    weekStart: LocalDate,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    val days = (0..6).map { weekStart.plusDays(it.toLong()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("把「${task.title.take(12)}」移到") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                days.forEachIndexed { index, day ->
                    TextButton(
                        onClick = { onPick(day.toString()) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            "${WEEKDAY_CN[index]}  ${day.monthValue} 月 ${day.dayOfMonth} 日" +
                                if (day.toString() == task.date) "（当前）" else "",
                        )
                    }
                }
                TextButton(onClick = { onPick("") }, modifier = Modifier.fillMaxWidth()) {
                    Text("移到「无日期」")
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun AddTaskDialog(initialDate: LocalDate, onDismiss: () -> Unit, onConfirm: (String, String, String) -> Unit) {
    var title by remember { mutableStateOf("") }
    var dateText by remember { mutableStateOf(initialDate.toString()) }
    var time by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("记一件事") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("标题") }, singleLine = true)
                OutlinedTextField(value = dateText, onValueChange = { dateText = it }, label = { Text("日期 YYYY-MM-DD") }, singleLine = true)
                OutlinedTextField(value = time, onValueChange = { time = it }, label = { Text("时间 HH:mm（可空）") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(
                enabled = title.isNotBlank() && (dateText.isBlank() || TaskRules.isValidDateKey(dateText)) && (time.isBlank() || TaskRules.isValidTime(time)),
                onClick = { onConfirm(title, dateText, time) },
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
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("标题") }, singleLine = true)
                OutlinedTextField(value = detail, onValueChange = { detail = it }, label = { Text("备忘") }, minLines = 2)
                OutlinedTextField(value = dateText, onValueChange = { dateText = it }, label = { Text("日期") }, singleLine = true)
                OutlinedTextField(value = time, onValueChange = { time = it }, label = { Text("时间") }, singleLine = true)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("none" to "立即", "1d" to "提前1天", "3d" to "提前3天", "1m" to "提前1月").forEach { (key, label) ->
                        FilterChip(selected = defer == key, onClick = { defer = key }, label = { Text(label) })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = title.isNotBlank() && (dateText.isBlank() || TaskRules.isValidDateKey(dateText)) && (time.isBlank() || TaskRules.isValidTime(time)),
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
