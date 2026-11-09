package com.deskweek.android.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * 与桌面版 `data/data.json` 的 items 字段一一对应。
 *
 * 字段名、默认值、语义必须与桌面版 `normalizeItem()` 保持一致，
 * 否则导入导出会丢数据。改动前先看 `docs/android-port-plan.md` §1。
 */
@Serializable
data class Task(
    val id: String,
    val title: String,
    val detail: String = "",
    val date: String = "",          // YYYY-MM-DD，可空
    val time: String = "",          // HH:mm，可空
    val done: Boolean = false,
    val completedAt: String = "",
    val deleted: Boolean = false,
    val deletedAt: String = "",
    val color: String = DEFAULT_COLORS[0],
    val defer: String = "none",     // "none" | "1m" | "Nd"
    val createdAt: String = "",
) {
    companion object {
        /** 与桌面版 manager.js / widget.js 的 COLORS 完全一致 */
        val DEFAULT_COLORS = listOf(
            "#9bd3c7", "#e7bd82", "#f39aa4", "#90b8ee", "#c8a6ef", "#a7d48d",
        )
    }
}

/**
 * 整个 data.json 的结构。`prefs` 保持为原始 JsonObject，
 * 这样桌面版的未知键（如 taskPanelHeight）能原样带回去，不被我们吃掉。
 */
@Serializable
data class StoreData(
    val initialized: Boolean = true,
    val items: List<Task> = emptyList(),
    val prefs: JsonObject = JsonObject(emptyMap()),
    val revision: Int = 0,
    val updatedAt: String = "",
)
