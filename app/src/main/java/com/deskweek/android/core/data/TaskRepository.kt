package com.deskweek.android.core.data

import android.content.Context
import com.deskweek.android.core.model.StoreData
import com.deskweek.android.core.model.Task
import com.deskweek.android.core.model.TaskRules
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.time.LocalDate
import java.util.UUID

/**
 * 全局唯一数据源。主程序、动态壁纸、小组件共用同一份 StateFlow，
 * 保证「App 里改一条，壁纸和小组件立刻跟着变」。
 *
 * 单进程应用，不需要桌面版那套多窗口字段级合并（mergeStore），
 * 但保留 revision 以便未来跨设备同步。
 */
object TaskRepository {

    private lateinit var store: TaskStore
    private val _state = MutableStateFlow(StoreData())

    val state: StateFlow<StoreData> = _state.asStateFlow()

    var lastLoadRecovered: Boolean = false
        private set

    fun init(context: Context) {
        if (::store.isInitialized) return
        store = TaskStore(File(context.filesDir, "data"))
        val result = store.load()
        lastLoadRecovered = result.recoveredFromBackup
        _state.value = result.data
    }

    /** 仅供测试注入 */
    fun initWithStore(taskStore: TaskStore) {
        store = taskStore
        _state.value = taskStore.load().data
    }

    fun persist(mutator: (StoreData) -> StoreData) {
        val next = mutator(_state.value)
        _state.value = store.save(next)
    }

    fun add(
        title: String,
        date: String,
        time: String = "",
        defer: String = "none",
        detail: String = "",
    ): Task {
        val now = java.time.Instant.now().toString()
        val color = TaskRules.nextColor(_state.value.items, date)
        val task = Task(
            id = UUID.randomUUID().toString(),
            title = title.trim().take(100),
            detail = detail.take(4000),
            date = if (TaskRules.isValidDateKey(date)) date else "",
            time = if (TaskRules.isValidTime(time)) time else "",
            color = color,
            defer = TaskRules.normalizeDefer(defer),
            createdAt = now,
        )
        persist { it.copy(items = TaskRules.reconcileColors(it.items + task)) }
        return task
    }

    fun update(task: Task) {
        persist { data ->
            data.copy(items = TaskRules.reconcileColors(data.items.map { if (it.id == task.id) task else it }))
        }
    }

    fun toggleDone(id: String) {
        val now = java.time.Instant.now().toString()
        persist { data ->
            data.copy(items = data.items.map {
                if (it.id != id) it else it.copy(
                    done = !it.done,
                    completedAt = if (!it.done) now else "",
                )
            })
        }
    }

    fun softDelete(id: String) {
        val now = java.time.Instant.now().toString()
        persist { data ->
            data.copy(items = data.items.map {
                if (it.id != id) it else it.copy(deleted = true, deletedAt = now)
            })
        }
    }

    fun restore(id: String) {
        persist { data ->
            data.copy(items = data.items.map {
                if (it.id != id) it else it.copy(deleted = false, deletedAt = "")
            })
        }
    }

    fun purge(id: String) {
        persist { data -> data.copy(items = data.items.filterNot { it.id == id }) }
    }

    fun active(today: LocalDate = LocalDate.now()): List<Task> =
        TaskRules.sortTasks(_state.value.items.filter { !it.deleted }, today)

    fun on(date: String, includeDone: Boolean = true): List<Task> =
        TaskRules.sortTasks(
            _state.value.items.filter { !it.deleted && it.date == date && (includeDone || !it.done) },
            LocalDate.now(),
        )

    fun deferred(today: LocalDate = LocalDate.now()): List<Task> =
        _state.value.items.filter { !it.deleted && TaskRules.isDeferred(it, today) }

    fun exportTo(target: File) = store.exportTo(target)

    fun importFrom(source: File, merge: Boolean = false) {
        _state.value = store.importFrom(source, merge)
    }

    fun storeFile(): File = store.file
}
