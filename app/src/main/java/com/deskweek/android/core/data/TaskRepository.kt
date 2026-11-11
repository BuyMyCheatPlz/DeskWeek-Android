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
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import androidx.glance.appwidget.updateAll
import com.deskweek.android.widget.DeskWeekWidget

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
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    fun reportError(error: Throwable) { _error.value = error.message ?: "保存失败，请重试" }
    fun clearError() { _error.value = null }
    private var widgetJob: Job? = null

    var lastLoadRecovered: Boolean = false
        private set

    @Synchronized fun init(context: Context) {
        if (::store.isInitialized) return
        store = TaskStore(File(context.filesDir, "data"))
        val result = store.load()
        lastLoadRecovered = result.recoveredFromBackup
        _state.value = result.data
        _error.value = result.loadError?.let { "数据及备份损坏，已阻止覆盖；请恢复有效文件" }
        val appContext = context.applicationContext
        widgetJob = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            state.collectLatest {
                try {
                    DeskWeekWidget().updateAll(appContext)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    // Task persistence already succeeded. Keep widget failures observable without
                    // reporting the task itself as unsaved or logging any task content.
                    android.util.Log.e("DeskWeekWidget", "Unable to refresh widgets", error)
                }
            }
        }
    }

    /** 仅供测试注入 */
    @Synchronized fun initWithStore(taskStore: TaskStore) {
        store = taskStore
        _state.value = taskStore.load().data
        _error.value = null
    }

    @Synchronized fun persist(mutator: (StoreData) -> StoreData) {
        val next = mutator(_state.value)
        _state.value = store.save(next)
    }

    @Synchronized fun add(
        title: String,
        date: String,
        time: String = "",
        defer: String = "none",
        detail: String = "",
    ): Task {
        val now = java.time.Instant.now().toString()
        val color = TaskRules.nextColor(_state.value.items, date)
        require(title.isNotBlank()) { "标题不能为空" }
        val task = TaskRules.normalize(Task(
            id = UUID.randomUUID().toString(),
            title = title.trim().take(100),
            detail = detail.take(4000),
            date = if (TaskRules.isValidDateKey(date)) date else "",
            time = if (TaskRules.isValidTime(time)) time else "",
            color = color,
            defer = TaskRules.normalizeDefer(defer),
            createdAt = now,
        ), now)
        persist { it.copy(items = TaskRules.reconcileColors(it.items + task)) }
        return task
    }

    @Synchronized fun update(task: Task, before: Task? = null) {
        persist { data ->
            data.copy(items = TaskRules.reconcileColors(data.items.map { current ->
                if (current.id != task.id) current else {
                    val edited = if (before == null) task else current.copy(
                        title = mergeEdited(before.title, task.title, current.title),
                        detail = mergeEdited(before.detail, task.detail, current.detail),
                        date = mergeEdited(before.date, task.date, current.date),
                        time = mergeEdited(before.time, task.time, current.time),
                        defer = mergeEdited(before.defer, task.defer, current.defer),
                    )
                    TaskRules.normalize(edited, java.time.Instant.now().toString())
                }
            }))
        }
    }

    /** AI proposals are accepted as one transaction, so retries cannot duplicate a partial batch. */
    @Synchronized fun addAll(entries: List<Task>) {
        require(entries.all { it.title.isNotBlank() }) { "标题不能为空" }
        if (entries.isEmpty()) return
        val now = java.time.Instant.now().toString()
        val tasks = entries.map { TaskRules.normalize(it.copy(id = UUID.randomUUID().toString(), createdAt = now), now) }
        persist { it.copy(items = TaskRules.reconcileColors(it.items + tasks)) }
    }

    private fun mergeEdited(before: String, incoming: String, actual: String): String {
        if (incoming == before) return actual
        check(actual == before || actual == incoming) { "任务内容已被修改，请重新打开编辑后重试" }
        return incoming
    }

    @Synchronized fun toggleDone(id: String) {
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

    @Synchronized fun softDelete(id: String) {
        val now = java.time.Instant.now().toString()
        persist { data ->
            data.copy(items = data.items.map {
                if (it.id != id) it else it.copy(deleted = true, deletedAt = now)
            })
        }
    }

    @Synchronized fun restore(id: String) {
        persist { data ->
            data.copy(items = data.items.map {
                if (it.id != id) it else it.copy(deleted = false, deletedAt = "")
            })
        }
    }

    @Synchronized fun purge(id: String) {
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

    @Synchronized fun exportTo(target: File) = store.exportTo(target)

    @Synchronized fun importFrom(source: File, merge: Boolean = false) {
        _state.value = store.importFrom(source, merge)
    }

    fun storeFile(): File = store.file
}
