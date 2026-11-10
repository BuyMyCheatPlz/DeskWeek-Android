package com.deskweek.android.core.data

import com.deskweek.android.core.model.Task
import com.deskweek.android.core.model.StoreData
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test

class TaskRepositoryTest {
    @Test fun `batch creation is one durable commit and invalid batches leave no partial tasks`() {
        TaskRepository.initWithStore(TaskStore(Files.createTempDirectory("repository-batch").toFile()))
        assertThrows(IllegalArgumentException::class.java) {
            TaskRepository.addAll(listOf(Task("", "valid"), Task("", "")))
        }
        assertTrue(TaskRepository.state.value.items.isEmpty())
        TaskRepository.addAll(listOf(Task("", "one"), Task("", "two")))
        assertEquals(2, TaskRepository.state.value.items.size)
        assertEquals(1, TaskRepository.state.value.revision)
        assertEquals(2, TaskRepository.state.value.items.map { it.id }.toSet().size)
    }
    @Test fun `simultaneous additions from independent callers are not lost`() {
        TaskRepository.initWithStore(TaskStore(Files.createTempDirectory("repository").toFile()))
        val start = CountDownLatch(1)
        val workers = (1..12).map { index -> thread {
            start.await(); TaskRepository.add("task-$index", "2026-11-10")
        } }
        start.countDown(); workers.forEach { it.join() }
        assertEquals(12, TaskRepository.state.value.items.size)
    }
    @Test fun `invalid date and time cannot create a task that changes date after restart`() {
        val store = TaskStore(Files.createTempDirectory("repository-normalize").toFile())
        TaskRepository.initWithStore(store)
        TaskRepository.add("task", "", "12:00", "3d")
        assertEquals(TaskRepository.state.value.items, store.load().data.items)
    }
    @Test fun `stale edit cannot resurrect a permanently deleted task`() {
        TaskRepository.initWithStore(TaskStore(Files.createTempDirectory("repository-delete").toFile()))
        val task = TaskRepository.add("task", "2026-11-10")
        TaskRepository.purge(task.id)
        TaskRepository.update(task.copy(title = "old editor"))
        assertTrue(TaskRepository.state.value.items.isEmpty())
    }
    @Test fun `editing title preserves completion changed while editor was open`() {
        TaskRepository.initWithStore(TaskStore(Files.createTempDirectory("repository-edit").toFile()))
        val before = TaskRepository.add("before", "2026-11-10")
        TaskRepository.toggleDone(before.id)
        TaskRepository.update(before.copy(title = "after"), before = before)
        assertTrue(TaskRepository.state.value.items.single().done)
        assertEquals("after", TaskRepository.state.value.items.single().title)
    }
    @Test fun `stale same field edit reports a conflict instead of overwriting`() {
        TaskRepository.initWithStore(TaskStore(Files.createTempDirectory("repository-conflict").toFile()))
        val before = TaskRepository.add("before", "2026-11-10")
        TaskRepository.update(before.copy(title = "first"), before)
        assertThrows(IllegalStateException::class.java) { TaskRepository.update(before.copy(title = "second"), before) }
        assertEquals("first", TaskRepository.state.value.items.single().title)
    }
}
