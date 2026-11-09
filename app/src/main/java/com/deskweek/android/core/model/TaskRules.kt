package com.deskweek.android.core.model

import java.time.LocalDate
import java.time.YearMonth

/**
 * 桌面端业务规则的 Kotlin 移植。全部为纯函数，便于单测。
 *
 * 逐条对应 `src/renderer/manager/manager.js`：
 * - normalizeDefer / visibleFromKey / isTaskVisible
 * - subtractMonthsClamped
 * - taskSortRank / sortItems / sortCompletedItems
 * - nextTaskColor / reconcileTaskColors
 */
object TaskRules {

    private val DATE_RE = Regex("""^\d{4}-\d{2}-\d{2}$""")
    private val TIME_RE = Regex("""^(?:[01]\d|2[0-3]):[0-5]\d$""")
    private val DEFER_DAYS_RE = Regex("""^[1-9]\d*d$""")

    fun isValidDateKey(value: String): Boolean {
        if (!DATE_RE.matches(value)) return false
        return try {
            LocalDate.parse(value).toString() == value
        } catch (e: Exception) {
            false
        }
    }

    fun isValidTime(value: String): Boolean = TIME_RE.matches(value)

    /** "none" | "1m" | "Nd"，非法值一律归一为 none */
    fun normalizeDefer(value: String?): String {
        val text = (value ?: "none").lowercase()
        return if (text == "1m" || DEFER_DAYS_RE.matches(text)) text else "none"
    }

    /** 与 JS 的 subtractMonthsClamped 一致：目标月天数不足时夹到月末 */
    fun subtractMonthsClamped(date: LocalDate, months: Int): LocalDate {
        val target = date.minusMonths(months.toLong())
        val lastDay = YearMonth.from(target).lengthOfMonth()
        return target.withDayOfMonth(minOf(date.dayOfMonth, lastDay))
    }

    /** 任务开始出现在「待办」列表的日期；date 非法时返回空串 */
    fun visibleFrom(task: Task): String {
        if (!isValidDateKey(task.date)) return ""
        val date = LocalDate.parse(task.date)
        val defer = normalizeDefer(task.defer)
        return when {
            defer.endsWith("m") -> subtractMonthsClamped(date, defer.dropLast(1).toIntOrNull() ?: 1).toString()
            defer.endsWith("d") -> date.minusDays((defer.dropLast(1).toIntOrNull() ?: 0).toLong()).toString()
            else -> task.date
        }
    }

    fun isVisible(task: Task, today: LocalDate): Boolean {
        if (!isValidDateKey(task.date)) return true
        val defer = normalizeDefer(task.defer)
        if (defer == "none") return true
        return today.toString() >= visibleFrom(task)
    }

    fun isDeferred(task: Task, today: LocalDate): Boolean = !isVisible(task, today)

    fun deferLabel(task: Task): String = when (val value = normalizeDefer(task.defer)) {
        "none" -> "立即显示"
        "1m" -> "提前1个月"
        else -> "提前${value.dropLast(1)}天"
    }

    /** 0 = 过期未完成，1 = 无日期，2 = 有日期 */
    fun sortRank(task: Task, today: LocalDate): Int {
        val dated = isValidDateKey(task.date)
        if (!task.done && dated && task.date < today.toString()) return 0
        if (!dated) return 1
        return 2
    }

    fun sortTasks(tasks: List<Task>, today: LocalDate): List<Task> =
        tasks.sortedWith(compareBy<Task> { sortRank(it, today) }
            .thenComparator { a, b ->
                val rank = sortRank(a, today)
                if (rank == 1) {
                    a.createdAt.compareTo(b.createdAt)
                } else {
                    val ka = "${a.date}T${a.time.ifBlank { "00:00" }}"
                    val kb = "${b.date}T${b.time.ifBlank { "00:00" }}"
                    ka.compareTo(kb).takeIf { it != 0 } ?: a.createdAt.compareTo(b.createdAt)
                }
            })

    fun sortCompleted(tasks: List<Task>): List<Task> =
        tasks.sortedByDescending { if (it.completedAt.isNotBlank()) it.completedAt else it.createdAt }

    /** 同一天内第 N 个任务的颜色，按当日未删除任务数轮转 */
    fun nextColor(all: List<Task>, date: String): String {
        val count = all.count { !it.deleted && it.date == date }
        return Task.DEFAULT_COLORS[count % Task.DEFAULT_COLORS.size]
    }

    /** 颜色非法或重复时按日期重排，保证同日任务颜色可区分 */
    fun reconcileColors(tasks: List<Task>): List<Task> {
        val seen = mutableMapOf<String, Int>()
        return tasks.map { task ->
            val index = seen.getOrElse(task.date) { 0 }
            val expected = if (task.date.isBlank()) {
                task.color.takeIf { it in Task.DEFAULT_COLORS } ?: Task.DEFAULT_COLORS[0]
            } else {
                Task.DEFAULT_COLORS[index % Task.DEFAULT_COLORS.size]
            }
            seen[task.date] = index + 1
            if (task.color == expected) task else task.copy(color = expected)
        }
    }

    /** 归一化单个任务，等价桌面版 normalizeItem() */
    fun normalize(task: Task, now: String): Task {
        val time = if (isValidTime(task.time)) task.time else ""
        val date = when {
            isValidDateKey(task.date) -> task.date
            time.isNotBlank() -> now.substringBefore("T")
            else -> ""
        }
        val createdAt = if (isIsoInstant(task.createdAt)) task.createdAt else now
        val completedAt = if (task.done) {
            if (isIsoInstant(task.completedAt)) task.completedAt else createdAt
        } else ""
        return task.copy(
            title = task.title.trim().take(100).ifBlank { "未命名事项" },
            detail = task.detail.take(4000),
            date = date,
            time = time,
            completedAt = completedAt,
            deletedAt = if (task.deletedAt.isNotBlank()) task.deletedAt else "",
            color = if (task.color in Task.DEFAULT_COLORS) task.color else Task.DEFAULT_COLORS[0],
            defer = if (date.isNotBlank()) normalizeDefer(task.defer) else "none",
            createdAt = createdAt,
        )
    }

    private fun isIsoInstant(value: String): Boolean =
        value.isNotBlank() && runCatching { java.time.Instant.parse(value) }.isSuccess
}
