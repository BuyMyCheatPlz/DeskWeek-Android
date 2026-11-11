package com.deskweek.android.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.TextUnit
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.background
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.deskweek.android.MainActivity
import com.deskweek.android.R
import com.deskweek.android.core.data.TaskRepository
import com.deskweek.android.core.model.Task
import com.deskweek.android.core.model.TaskRules
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * 透明主屏周历小组件（首版主形态）。
 *
 * 设计依据 `docs/android-port-plan.md` §3：
 * - 按可用尺寸分支渲染，不按机型/像素判断
 * - 透明背景，保留用户壁纸；文字阴影由系统小组件渲染兜底
 * - 任务总量过多时显示"还有 N 项"，不无限缩小字号
 * - 本地提交后主动 updateAll，不做分钟级轮询
 */
class DeskWeekWidget : GlanceAppWidget() {

    companion object {
        val TASK_ID = ActionParameters.Key<String>("taskId")

        // 用 Compose Color 构造 ColorProvider（用资源 id 的构造是 Glance 内部受限 API）
        private val INK = Color(0xE61E1E22)
        private val INK_DIM = Color(0x99202020)
        private val ACCENT = Color(0xFF2F6F5E)
        private val SURFACE = Color(0xECF6F5F2)
        private data class Ink(val text: Color, val dim: Color)
        private data class Appearance(val transparent: Boolean, val lightText: Boolean)
        private val LocalInk = staticCompositionLocalOf { Ink(INK, INK_DIM) }

        private val SMALL = DpSize(110.dp, 40.dp)
        private val MEDIUM = DpSize(180.dp, 110.dp)
        private val LARGE = DpSize(250.dp, 180.dp)
    }

    override val sizeMode: SizeMode = SizeMode.Exact

    data class Snapshot(
        val today: LocalDate,
        val weekDays: List<LocalDate>,
        val byDate: Map<String, List<Task>>,
        val overflow: Map<String, Int>,
        val undated: List<Task>,
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        TaskRepository.init(context)
        // updateAll recomposes an active Glance session; it does not rerun provideGlance.
        // Observe tasks inside the composition so an initially empty snapshot cannot get stuck.
        provideContent {
            val data by TaskRepository.state.collectAsState()
            val snapshot = loadSnapshot(data.items)
            val prefs = remember { context.applicationContext.getSharedPreferences("deskweek_widget", Context.MODE_PRIVATE) }
            fun readAppearance() = Appearance(prefs.getBoolean("transparent", false), prefs.getBoolean("lightText", true))
            var appearance by remember { mutableStateOf(readAppearance()) }
            DisposableEffect(prefs) {
                val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> appearance = readAppearance() }
                prefs.registerOnSharedPreferenceChangeListener(listener)
                appearance = readAppearance()
                onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
            }
            val ink = if (appearance.transparent && appearance.lightText) Ink(Color.White, Color(0xDDFFFFFF)) else Ink(INK, INK_DIM)
            CompositionLocalProvider(LocalInk provides ink) { WidgetBody(snapshot, appearance.transparent, context.resources.configuration.fontScale.coerceAtLeast(1f)) }
        }
    }

    private fun loadSnapshot(allItems: List<Task>): Snapshot {
        val today = LocalDate.now()
        val start = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val days = (0..6).map { start.plusDays(it.toLong()) }
        val items = allItems.filter { !it.deleted && it.date.isNotBlank() }
        val grouped = items.groupBy { it.date }
        val byDate = mutableMapOf<String, List<Task>>()
        val overflow = mutableMapOf<String, Int>()
        days.forEach { day ->
            val key = day.toString()
            val sorted = TaskRules.sortTasks(grouped[key].orEmpty(), today)
            byDate[key] = sorted
            if (sorted.size > 3) overflow[key] = sorted.size - 3
        }
        val undated = TaskRules.sortTasks(allItems.filter { !it.deleted && it.date.isBlank() }, today)
        return Snapshot(today, days, byDate, overflow, undated)
    }

    @Composable
    private fun WidgetBody(snapshot: Snapshot, transparent: Boolean, fontScale: Float) {
        val background = if (transparent) ColorProvider(Color.Transparent) else ColorProvider(SURFACE)
        val size = LocalSize.current
        when {
            size.height < 96.dp * fontScale -> CompactBody(snapshot, background, fontScale)
            size.width < 240.dp -> SmallBody(snapshot, background, fontScale)
            size.width < 480.dp || size.height < 180.dp * fontScale -> MediumBody(snapshot, background, fontScale)
            else -> LargeBody(snapshot, background, fontScale)
        }
    }

    private fun summaryTasks(snapshot: Snapshot): List<Task> =
        snapshot.byDate[snapshot.today.toString()].orEmpty() + snapshot.undated +
            snapshot.weekDays.filter { it != snapshot.today }.flatMap { snapshot.byDate[it.toString()].orEmpty() }

    private fun taskCount(total: Int, slots: Int): Int = if (total > slots && slots > 1) slots - 1 else minOf(total, slots)

    @Composable
    private fun CompactBody(snapshot: Snapshot, background: ColorProvider, fontScale: Float) {
        val tasks = summaryTasks(snapshot)
        val date = "${snapshot.today.monthValue}/${snapshot.today.dayOfMonth}"
        val twoLines = LocalSize.current.height >= 40.dp * fontScale + 8.dp
        val shortLabel = LocalSize.current.width < 240.dp
        val caption = if (shortLabel && tasks.firstOrNull()?.date?.isBlank() == true) "未排期 ${snapshot.undated.size} 项" else "本周 ${tasks.size} 项"
        val verticalPadding = if (LocalSize.current.height < 48.dp * fontScale) 0.dp else 4.dp
        Column(GlanceModifier.fillMaxSize().background(background).cornerRadius(12.dp).padding(horizontal = 8.dp, vertical = verticalPadding)) {
            // Put the task first: even launchers that over-report available height
            // must not spend their only visible line on decoration.
            if (tasks.isEmpty()) Text(if (twoLines) "今天空着" else "$date · 今天空着", style = dimStyle(), maxLines = 1, modifier = GlanceModifier.clickable(actionStartActivity(MainActivity::class.java)))
            else TaskLine(tasks.first(), summaryToday = snapshot.today, shortLabel = shortLabel, fontSize = if (twoLines) 14.sp else 13.sp)
            if (twoLines) Text("$date · $caption", style = dimStyle(), maxLines = 1)
        }
    }

    @Composable
    private fun SmallBody(snapshot: Snapshot, background: ColorProvider, fontScale: Float) {
        val tasks = summaryTasks(snapshot)
        val slots = ((LocalSize.current.height - 16.dp - 20.dp * fontScale) / (20.dp * fontScale)).toInt().coerceIn(1, 3)
        val shown = taskCount(tasks.size, slots)
        Column(modifier = GlanceModifier.fillMaxSize().background(background).cornerRadius(16.dp).padding(8.dp)) {
            Row(verticalAlignment = Alignment.Vertical.CenterVertically) {
                Text(
                    text = "${snapshot.today.monthValue}/${snapshot.today.dayOfMonth}",
                    style = TextStyle(
                        fontWeight = FontWeight.Bold,
                        color = ColorProvider(LocalInk.current.text),
                    ),
                )
                Spacer(modifier = GlanceModifier.width(6.dp))
                Text(text = "还剩 ${tasks.count { !it.done }} 项", style = dimStyle())
            }
            tasks.take(shown).forEach { TaskLine(it, summaryToday = snapshot.today) }
            if (tasks.size > shown && slots > shown) Text("还有 ${tasks.size - shown} 项", modifier = GlanceModifier.clickable(actionStartActivity(MainActivity::class.java)), style = dimStyle())
            if (tasks.isEmpty()) Text(text = "今天空着", style = dimStyle())
        }
    }

    @Composable
    private fun MediumBody(snapshot: Snapshot, background: ColorProvider, fontScale: Float) {
        val colWidth = (LocalSize.current.width - 16.dp) / 7
        Column(modifier = GlanceModifier.fillMaxSize().background(background).cornerRadius(16.dp).padding(8.dp)) {
            Header(snapshot)
            Spacer(modifier = GlanceModifier.height(6.dp))
            Row(modifier = GlanceModifier.fillMaxWidth()) {
                snapshot.weekDays.forEach { day ->
                    Text(if (day == snapshot.today) "今" else day.dayOfMonth.toString(), modifier = GlanceModifier.width(colWidth), style = dimStyle())
                }
            }
            val tasks = summaryTasks(snapshot)
            val slots = ((LocalSize.current.height - 22.dp - 40.dp * fontScale) / (20.dp * fontScale)).toInt().coerceIn(1, 4)
            val shown = taskCount(tasks.size, slots)
            tasks.take(shown).forEach { TaskLine(it, summaryToday = snapshot.today) }
            if (tasks.size > shown && slots > shown) Text("还有 ${tasks.size - shown} 项", modifier = GlanceModifier.clickable(actionStartActivity(MainActivity::class.java)), style = dimStyle())
            if (tasks.isEmpty()) Text("本周空着", style = dimStyle())
        }
    }

    @Composable
    private fun LargeBody(snapshot: Snapshot, background: ColorProvider, fontScale: Float) {
        val colWidth = (LocalSize.current.width - 20.dp) / 7
        val undatedRows = minOf(snapshot.undated.size, 2) + if (snapshot.undated.isEmpty()) 0 else 1
        val slots = ((LocalSize.current.height - 34.dp - (40.dp + 20.dp * undatedRows) * fontScale) / (20.dp * fontScale)).toInt().coerceIn(1, 4)
        Column(modifier = GlanceModifier.fillMaxSize().background(background).cornerRadius(16.dp).padding(10.dp)) {
            Header(snapshot)
            Spacer(modifier = GlanceModifier.height(8.dp))
            Row(modifier = GlanceModifier.fillMaxWidth()) {
                snapshot.weekDays.forEach { day ->
                    DayColumn(day, snapshot, GlanceModifier.width(colWidth), slots)
                }
            }
            if (snapshot.undated.isNotEmpty()) {
                Spacer(modifier = GlanceModifier.height(6.dp))
                Text("未排期 ${snapshot.undated.size} 项", style = dimStyle())
                snapshot.undated.take(2).forEach { TaskLine(it) }
            }
        }
    }

    @Composable
    private fun Header(snapshot: Snapshot) {
        val start = snapshot.weekDays.first()
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.Vertical.CenterVertically,
        ) {
            Text(
                text = "${start.monthValue} 月",
                style = TextStyle(fontWeight = FontWeight.Bold, color = ColorProvider(LocalInk.current.text)),
            )
            Spacer(modifier = GlanceModifier.width(6.dp))
            Text(
                text = "DeskWeek",
                modifier = GlanceModifier.clickable(actionStartActivity(MainActivity::class.java)),
                style = dimStyle(),
            )
        }
    }

    @Composable
    private fun DayColumn(
        day: LocalDate,
        snapshot: Snapshot,
        modifier: GlanceModifier,
        slots: Int,
    ) {
        val isToday = day == snapshot.today
        val key = day.toString()
        val tasks = snapshot.byDate[key].orEmpty()

        Column(modifier = modifier.padding(horizontal = 1.dp)) {
            Text(
                text = if (isToday) "今" else "${day.dayOfMonth}",
                style = TextStyle(
                    fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                    color = if (isToday) ColorProvider(ACCENT) else ColorProvider(LocalInk.current.dim),
                ),
            )
            val shown = taskCount(tasks.size, slots)
            tasks.take(shown).forEach { TaskLine(it) }
            val hidden = tasks.size - shown
            if (hidden > 0 && slots > shown) {
                Text(text = "+$hidden", modifier = GlanceModifier.clickable(actionStartActivity(MainActivity::class.java)), style = dimStyle())
            }
        }
    }

    @Composable
    private fun TaskLine(task: Task, summaryToday: LocalDate? = null, shortLabel: Boolean = false, fontSize: TextUnit = 14.sp) {
        val dateLabel = if (task.date.isBlank()) { if (shortLabel) "" else "未排期 · " } else if (summaryToday != null && task.date != summaryToday.toString()) "${task.date.substring(5).replace('-', '/')} · " else ""
        Text(
            text = (if (task.done) "☑ " else "☐ ") + dateLabel + task.title,
            maxLines = 1,
            style = TextStyle(
                fontSize = fontSize,
                color = if (task.done) ColorProvider(LocalInk.current.dim) else ColorProvider(LocalInk.current.text),
            ),
            modifier = GlanceModifier
                .clickable(actionRunCallback<ToggleDoneAction>(actionParametersOf(TASK_ID to task.id))),
        )
    }

    @Composable
    private fun dimStyle() = TextStyle(color = ColorProvider(LocalInk.current.dim), fontSize = 12.sp)
}

/** 点任务勾选：先落本地库，再刷新所有组件实例 */
class ToggleDoneAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = parameters[DeskWeekWidget.TASK_ID] ?: return
        TaskRepository.init(context)
        TaskRepository.toggleDone(id)
        DeskWeekWidget().updateAll(context)
    }
}
