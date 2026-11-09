package com.deskweek.android.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.compose.ui.unit.dp
import com.deskweek.android.wallpaper.SampleTasks
import java.time.LocalDate

/**
 * 保底形态：主屏小组件。
 *
 * 当用户已经在用别的动态壁纸、无法启用 DeskWeek 壁纸时，
 * 这个小组件仍能在主屏显示今日待办。
 *
 * 与壁纸形态共享同一份数据（骨架阶段是 SampleTasks，P1 后换成 Room）。
 */
class DeskWeekWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            GlanceTheme {
                WidgetBody()
            }
        }
    }

    @Composable
    private fun WidgetBody() {
        val today = LocalDate.now().toString()
        val tasks = SampleTasks.items.filter { it.date == today }.take(3)

        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(ColorProvider(android.graphics.Color.argb(0xEC, 0xF6, 0xF5, 0xF2)))
                .padding(12.dp),
        ) {
            Text(
                text = "DeskWeek · $today",
                style = TextStyle(
                    fontWeight = FontWeight.Bold,
                    color = ColorProvider(android.graphics.Color.rgb(0x1E, 0x1E, 0x22)),
                ),
            )
            if (tasks.isEmpty()) {
                Text(
                    text = "今天没有安排",
                    style = TextStyle(color = ColorProvider(android.graphics.Color.rgb(0x66, 0x66, 0x66))),
                )
            } else {
                tasks.forEach { task ->
                    Text(
                        text = if (task.time.isBlank()) "· ${task.title}" else "· ${task.time} ${task.title}",
                        style = TextStyle(color = ColorProvider(android.graphics.Color.rgb(0x33, 0x33, 0x33))),
                    )
                }
            }
        }
    }
}
