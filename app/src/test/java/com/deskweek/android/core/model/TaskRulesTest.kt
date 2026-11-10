package com.deskweek.android.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class TaskRulesTest {
    @Test fun `time only task uses local day instead of UTC timestamp day`() {
        val task = TaskRules.normalize(Task("local-day", "morning", time = "08:00"),
            "2026-11-09T23:30:00Z", LocalDate.of(2026, 11, 10))
        assertEquals("2026-11-10", task.date)
    }
    @Test fun `color reconciliation matches desktop creation order and ignores trash`() {
        val old = task(id = "old", date = "2026-11-10", createdAt = "2026-11-01T00:00:00Z")
        val recent = task(id = "new", date = "2026-11-10", createdAt = "2026-11-02T00:00:00Z")
        val deleted = task(id = "deleted", date = "2026-11-10").copy(deleted = true)
        val result = TaskRules.reconcileColors(listOf(deleted, recent, old)).associateBy { it.id }
        assertEquals(Task.DEFAULT_COLORS[0], result.getValue("old").color)
        assertEquals(Task.DEFAULT_COLORS[1], result.getValue("new").color)
    }

    private val today = LocalDate.of(2026, 11, 9)

    private fun task(
        id: String = "t1",
        date: String = "",
        time: String = "",
        done: Boolean = false,
        defer: String = "none",
        createdAt: String = "2026-11-01T00:00:00Z",
        color: String = "#9bd3c7",
    ) = Task(
        id = id, title = "t$id", date = date, time = time,
        done = done, defer = defer, createdAt = createdAt, color = color,
    )

    @Test
    fun `defer none is always visible`() {
        assertTrue(TaskRules.isVisible(task(date = "2026-12-01", defer = "none"), today))
    }

    @Test
    fun `defer 3d becomes visible three days before`() {
        val t = task(date = "2026-11-12", defer = "3d")
        assertEquals("2026-11-09", TaskRules.visibleFrom(t))
        assertTrue(TaskRules.isVisible(t, today))
        assertFalse(TaskRules.isVisible(t, today.minusDays(1)))
    }

    @Test
    fun `defer 1m clamps to end of month`() {
        // 3 月 31 日往前一个月，2 月只有 28 天，应夹到 02-28
        val t = task(date = "2026-03-31", defer = "1m")
        assertEquals("2026-02-28", TaskRules.visibleFrom(t))
    }

    @Test
    fun `defer 1m on leap year clamps to 29`() {
        val t = task(date = "2028-03-31", defer = "1m")
        assertEquals("2028-02-29", TaskRules.visibleFrom(t))
    }

    @Test
    fun `task without date is always visible`() {
        assertTrue(TaskRules.isVisible(task(date = "", defer = "1m"), today))
    }

    @Test
    fun `sort puts overdue first then undated then dated`() {
        val overdue = task(id = "a", date = "2026-11-01")
        val undated = task(id = "b", date = "", createdAt = "2026-11-02T00:00:00Z")
        val future = task(id = "c", date = "2026-11-20")

        val sorted = TaskRules.sortTasks(listOf(future, undated, overdue), today)
        assertEquals(listOf("a", "b", "c"), sorted.map { it.id })
    }

    @Test
    fun `completed overdue task is not ranked as overdue`() {
        // 已完成的过期任务不该再占用 rank 0（过期区），而应落到有日期区
        val doneOverdue = task(id = "a", date = "2026-11-01", done = true)
        val undated = task(id = "b", date = "", createdAt = "2026-11-05T00:00:00Z")
        val sorted = TaskRules.sortTasks(listOf(doneOverdue, undated), today)
        assertEquals(listOf("b", "a"), sorted.map { it.id })
    }

    @Test
    fun `sort by date then time then createdAt`() {
        val a = task(id = "a", date = "2026-11-10", time = "09:00", createdAt = "2026-11-01T00:00:00Z")
        val b = task(id = "b", date = "2026-11-10", time = "08:00", createdAt = "2026-11-02T00:00:00Z")
        val c = task(id = "c", date = "2026-11-10", time = "08:00", createdAt = "2026-11-01T00:00:00Z")
        val sorted = TaskRules.sortTasks(listOf(a, b, c), today)
        assertEquals(listOf("c", "b", "a"), sorted.map { it.id })
    }

    @Test
    fun `color rotates by task count on the same day`() {
        val items = listOf(task(id = "1", date = "2026-11-10"), task(id = "2", date = "2026-11-10"))
        assertEquals(Task.DEFAULT_COLORS[2], TaskRules.nextColor(items, "2026-11-10"))
        assertEquals(Task.DEFAULT_COLORS[0], TaskRules.nextColor(items, "2026-11-11"))
    }

    @Test
    fun `normalize rejects broken date and keeps time only when valid`() {
        val now = "2026-11-09T10:00:00Z"
        val bad = Task(id = "x", title = "  ", date = "2026-13-40", time = "25:99", createdAt = "nope")
        val n = TaskRules.normalize(bad, now)
        assertEquals("", n.date)
        assertEquals("", n.time)
        assertEquals("未命名事项", n.title)
        assertEquals(now, n.createdAt)
    }

    @Test
    fun `normalize fills completedAt when done and clears it otherwise`() {
        val now = "2026-11-09T10:00:00Z"
        val done = TaskRules.normalize(Task(id = "a", title = "a", done = true), now)
        assertEquals(done.createdAt, done.completedAt)

        val undone = TaskRules.normalize(
            Task(id = "b", title = "b", done = false, completedAt = "2026-11-01T00:00:00Z"), now,
        )
        assertEquals("", undone.completedAt)
    }

    @Test
    fun `title is capped at 100 chars and detail at 4000`() {
        val now = "2026-11-09T10:00:00Z"
        val long = TaskRules.normalize(
            Task(id = "a", title = "标".repeat(150), detail = "细".repeat(5000)), now,
        )
        assertEquals(100, long.title.length)
        assertEquals(4000, long.detail.length)
    }
}
