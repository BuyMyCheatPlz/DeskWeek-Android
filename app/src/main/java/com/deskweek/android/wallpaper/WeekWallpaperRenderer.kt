package com.deskweek.android.wallpaper

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * 动态壁纸的周历渲染器（纯 Canvas，不依赖 Compose）。
 *
 * 与主程序的 Compose 周视图共用同一套视觉常量（6 色板、字号比例），
 * 保证"壁纸上看到的"和"App 里看到的"一致。
 */
class WeekWallpaperRenderer {

    companion object {
        /** 与桌面版 manager.js 的 COLORS 完全一致 */
        val PALETTE = intArrayOf(
            0xFF9BD3C7.toInt(),
            0xFFE7BD82.toInt(),
            0xFFF39AA4.toInt(),
            0xFF90B8EE.toInt(),
            0xFFC8A6EF.toInt(),
            0xFFA7D48D.toInt(),
        )
    }

    data class Style(
        val dark: Boolean = false,
        val startYRatio: Float = 0.20f,   // 首行距顶部比例，给状态栏/图标留白
        val maxTasksPerDay: Int = 4,
        val columnGapRatio: Float = 0.012f,
    )

    private val headerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
    }
    private val datePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val todayCircle = Paint(Paint.ANTI_ALIAS_FLAG)
    private val taskPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    fun draw(
        canvas: Canvas,
        width: Int,
        height: Int,
        today: LocalDate = LocalDate.now(),
        tasks: List<WallTask> = SampleTasks.items,
        style: Style = Style(),
    ) {
        if (width <= 0 || height <= 0) return

        val fg = if (style.dark) Color.WHITE else Color.rgb(0x1E, 0x1E, 0x22)
        val dim = if (style.dark) Color.argb(0xB0, 255, 255, 255) else Color.argb(0x99, 0x20, 0x20, 0x20)
        val rowH = height * 0.016f
        val baseText = width / 7f * 0.135f

        headerPaint.color = dim
        headerPaint.textSize = baseText * 0.72f
        datePaint.color = fg
        datePaint.textSize = baseText
        taskPaint.color = dim
        taskPaint.textSize = baseText * 0.62f

        val padding = width * 0.03f
        val gap = width * style.columnGapRatio
        val usable = width - padding * 2
        val colW = (usable - gap * 6) / 7f

        val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val days = (0..6).map { weekStart.plusDays(it.toLong()) }
        val top = height * style.startYRatio

        val byDate = tasks.groupBy { it.date }

        days.forEachIndexed { index, date ->
            val x = padding + index * (colW + gap)
            val isToday = date == today

            // 星期
            val label = WEEKDAY_LABELS[index]
            canvas.drawText(label, x, top, headerPaint)

            // 日号
            val dayText = date.dayOfMonth.toString()
            val dayBaseline = top + rowH * 3.1f
            if (isToday) {
                todayCircle.color = PALETTE[0]
                val r = baseText * 0.95f
                val cx = x + datePaint.measureText(dayText) / 2f
                rect.set(cx - r, dayBaseline - baseText * 1.05f, cx + r, dayBaseline + baseText * 0.42f)
                canvas.drawRoundRect(rect, r, r, todayCircle)
            }
            datePaint.color = if (isToday) Color.rgb(0x1B, 0x3B, 0x33) else fg
            canvas.drawText(dayText, x, dayBaseline, datePaint)

            // 当日任务
            var y = dayBaseline + rowH * 3f
            val dayTasks = byDate[date.toLocalDateKey()].orEmpty()
                .take(style.maxTasksPerDay)
            val maxTextW = colW - baseText * 0.42f

            dayTasks.forEach { task ->
                val dotRadius = baseText * 0.16f
                val cy = y - baseText * 0.24f
                dotPaint.color = if (task.done) dim else task.color
                canvas.drawCircle(x + dotRadius, cy, dotRadius, dotPaint)

                val text = if (task.time.isNotBlank()) "${task.time} ${task.title}" else task.title
                taskPaint.color = if (task.done) dim else fg
                taskPaint.isStrikeThruText = task.done
                val clipped = TextUtils.ellipsize(text, taskPaint, maxTextW, TextUtils.TruncateAt.END)
                canvas.drawText(clipped, 0, clipped.length, x + dotRadius * 3f, y, taskPaint)
                taskPaint.isStrikeThruText = false
                y += rowH * 2.2f
            }

            // 列分隔线
            if (index < 6) {
                dividerPaint.color = if (style.dark) Color.argb(0x2A, 255, 255, 255) else Color.argb(0x20, 0, 0, 0)
                dividerPaint.strokeWidth = Math.max(1f, width / 900f)
                val lx = x + colW + gap / 2f
                canvas.drawLine(lx, top - rowH * 1.5f, lx, top + rowH * 26f, dividerPaint)
            }
        }
    }

    private fun LocalDate.toLocalDateKey(): String =
        "%04d-%02d-%02d".format(year, monthValue, dayOfMonth)

    private val WEEKDAY_LABELS = arrayOf("一", "二", "三", "四", "五", "六", "日")
}
