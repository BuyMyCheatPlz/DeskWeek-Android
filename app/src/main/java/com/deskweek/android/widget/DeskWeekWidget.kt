package com.deskweek.android.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
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
            val prefs = context.getSharedPreferences("deskweek_widget", Context.MODE_PRIVATE)
            val transparent = prefs.getBoolean("transparent", false)
            val lightText = prefs.getBoolean("lightText", true)
            val ink = if (transparent && lightText) Ink(Color.White, Color(0xDDFFFFFF)) else Ink(INK, INK_DIM)
            CompositionLocalProvider(LocalInk provides ink) { WidgetBody(snapshot, transparent) }
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
    private fun WidgetBody(snapshot: Snapshot, transparent: Boolean) {
        val background = if (transparent) ColorProvider(Color.Transparent) else ColorProvider(SURFACE)
        val size = LocalSize.current
        when {
            size.width < 240.dp -> SmallBody(snapshot, background)
            size.width < 480.dp -> MediumBody(snapshot, background)
            else -> LargeBody(snapshot, background)
        }
    }

    @Composable
    private fun SmallBody(snapshot: Snapshot, background: ColorProvider) {
        val todayKey = snapshot.today.toString()
        val tasks = snapshot.byDate[todayKey].orEmpty() + snapshot.undated
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
            tasks.take(2).forEach { TaskLine(it) }
            if (tasks.size > 2) Text("还有 ${tasks.size - 2} 项", modifier = GlanceModifier.clickable(actionStartActivity(MainActivity::class.java)), style = dimStyle())
            if (tasks.isEmpty()) Text(text = "今天空着", style = dimStyle())
        }
    }

    @Composable
    private fun MediumBody(snapshot: Snapshot, background: ColorProvider) {
        val colWidth = (LocalSize.current.width - 16.dp) / 7
        Column(modifier = GlanceModifier.fillMaxSize().background(background).cornerRadius(16.dp).padding(8.dp)) {
            Header(snapshot)
            Spacer(modifier = GlanceModifier.height(6.dp))
            Row(modifier = GlanceModifier.fillMaxWidth()) {
                snapshot.weekDays.forEach { day ->
                    Text(if (day == snapshot.today) "今" else day.dayOfMonth.toString(), modifier = GlanceModifier.width(colWidth), style = dimStyle())
                }
            }
            val todayTasks = snapshot.byDate[snapshot.today.toString()].orEmpty() + snapshot.undated
            todayTasks.take(3).forEach { TaskLine(it) }
            if (todayTasks.size > 3) Text("还有 ${todayTasks.size - 3} 项", modifier = GlanceModifier.clickable(actionStartActivity(MainActivity::class.java)), style = dimStyle())
        }
    }

    @Composable
    private fun LargeBody(snapshot: Snapshot, background: ColorProvider) {
        val colWidth = (LocalSize.current.width - 20.dp) / 7
        Column(modifier = GlanceModifier.fillMaxSize().background(background).cornerRadius(16.dp).padding(10.dp)) {
            Header(snapshot)
            Spacer(modifier = GlanceModifier.height(8.dp))
            Row(modifier = GlanceModifier.fillMaxWidth()) {
                snapshot.weekDays.forEach { day ->
                    DayColumn(day, snapshot, GlanceModifier.width(colWidth), compact = false)
                }
            }
            if (snapshot.undated.isNotEmpty()) {
                Spacer(modifier = GlanceModifier.height(6.dp))
                Text("未排期 ${snapshot.undated.size} 项", style = dimStyle())
                snapshot.undated.take(2).forEach { TaskLine(it) }
                if (snapshot.undated.size > 2) Text(
                    "还有 ${snapshot.undated.size - 2} 项未排期",
                    modifier = GlanceModifier.clickable(actionStartActivity(MainActivity::class.java)), style = dimStyle(),
                )
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
        compact: Boolean,
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
            tasks.take(if (compact) 1 else 3).forEach { TaskLine(it, dense = compact) }
            val hidden = tasks.size - if (compact) 1 else 3
            if (hidden > 0) {
                Text(text = "+$hidden", modifier = GlanceModifier.clickable(actionStartActivity(MainActivity::class.java)), style = dimStyle())
            }
        }
    }

    @Composable
    private fun TaskLine(task: Task, dense: Boolean = false) {
        Text(
            text = (if (task.done) "☑ " else "☐ ") + (if (task.date.isBlank()) "未排期 · " else "") + task.title,
            maxLines = 1,
            style = TextStyle(
                color = if (task.done) ColorProvider(LocalInk.current.dim) else ColorProvider(LocalInk.current.text),
            ),
            modifier = GlanceModifier
                .clickable(actionRunCallback<ToggleDoneAction>(actionParametersOf(TASK_ID to task.id))),
        )
    }

    @Composable
    private fun dimStyle() = TextStyle(color = ColorProvider(LocalInk.current.dim))
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
