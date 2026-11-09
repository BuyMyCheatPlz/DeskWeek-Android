package com.deskweek.android.wallpaper

import com.deskweek.android.core.model.Task

/**
 * 壁纸层用的极简任务视图模型。
 * 字段与桌面版 data.json 对齐，由 [Task] 直接映射，不做二次加工。
 */
data class WallTask(
    val id: String,
    val title: String,
    val date: String,        // YYYY-MM-DD
    val time: String,        // HH:mm，可空
    val done: Boolean,
    val color: Int,          // ARGB
)

fun Task.toWallTask(): WallTask = WallTask(
    id = id,
    title = title,
    date = date,
    time = time,
    done = done,
    color = runCatching { android.graphics.Color.parseColor(color) }.getOrDefault(0xFF9BD3C7.toInt()),
)
