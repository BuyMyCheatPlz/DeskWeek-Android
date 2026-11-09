package com.deskweek.android.wallpaper

/**
 * 壁纸层用的极简任务视图模型。
 *
 * 注意：这是 P5 骨架阶段的占位实现，真实数据后续接 Room（P1）后替换。
 * 字段命名刻意与桌面版 data.json 对齐，方便替换时不改渲染层。
 */
data class WallTask(
    val id: String,
    val title: String,
    val date: String,        // YYYY-MM-DD
    val time: String,        // HH:mm，可空
    val done: Boolean,
    val color: Int,          // ARGB
)

object SampleTasks {
    val items: List<WallTask> = listOf(
        WallTask("1", "评审 Android 方案", "2026-11-09", "10:00", false, 0xFF9BD3C7.toInt()),
        WallTask("2", "写动态壁纸渲染器", "2026-11-09", "14:30", false, 0xFFE7BD82.toInt()),
        WallTask("3", "对接 data.json", "2026-11-10", "", false, 0xFFF39AA4.toInt()),
        WallTask("4", "农历算法对比测试", "2026-11-11", "", false, 0xFF90B8EE.toInt()),
        WallTask("5", "小组件保底方案", "2026-11-12", "", false, 0xFFC8A6EF.toInt()),
        WallTask("6", "真机验证国产 ROM", "2026-11-13", "09:00", false, 0xFFA7D48D.toInt()),
    )
}
