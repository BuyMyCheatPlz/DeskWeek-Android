package com.deskweek.android.core.data

import com.deskweek.android.core.model.StoreData
import com.deskweek.android.core.model.Task
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.*
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 与桌面版 `data/data.json` 完全同构的本地存储。
 *
 * 刻意不使用 Room：桌面版的数据契约就是「一个 JSON 文件 + 上一版备份」，
 * 我们照搬这套语义，导入导出就退化成文件复制，跨端一致性由结构保证而不是靠映射表。
 *
 * 对应桌面版 `src/main/storage/store.cjs`：
 * - writeAtomic：写 .tmp → fsync → rename
 * - 主文件损坏时回退 `.backup`，并把损坏文件另存 `.corrupt-<ts>`
 * - 每次保存前把上一版主文件滚成 `.backup`
 */
class TaskStore(
    private val dir: File,
    private val json: Json = DEFAULT_JSON,
) {
    companion object {
        const val FILE_NAME = "data.json"

        val DEFAULT_JSON = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }

    val file: File get() = File(dir, FILE_NAME)
    val backupFile: File get() = File(dir, "$FILE_NAME.backup")

    data class LoadResult(
        val data: StoreData,
        /** 曾经从备份或损坏状态恢复过，UI 需要提示用户 */
        val recoveredFromBackup: Boolean = false,
        val loadError: String? = null,
    )

    @Synchronized fun load(): LoadResult {
        var damaged = false

        runCatching {
            val text = file.readText(Charsets.UTF_8)
            return LoadResult(parse(text))
        }.onFailure { if (file.exists()) damaged = true }

        if (backupFile.exists()) {
            runCatching {
                val text = backupFile.readText(Charsets.UTF_8)
                val data = parse(text)
                if (file.exists()) {
                    val corrupt = File(dir, "$FILE_NAME.corrupt-${System.currentTimeMillis()}")
                    runCatching { file.copyTo(corrupt, overwrite = true) }
                }
                writeAtomic(file, encode(data))
                return LoadResult(data, recoveredFromBackup = true)
            }.onFailure { damaged = true }
        }

        return LoadResult(
            data = StoreData(initialized = false),
            loadError = if (damaged) "unreadable-or-invalid" else null,
        )
    }

    /** 解析并归一化；结构非法直接抛异常，由调用方交给 load() 的恢复逻辑 */
    fun parse(text: String): StoreData {
        if (text.isBlank()) throw IllegalArgumentException("empty store")
        val root = json.parseToJsonElement(text) as? JsonObject
            ?: throw IllegalArgumentException("任务文件必须是 JSON 对象")
        val array = root["items"] as? JsonArray
            ?: throw IllegalArgumentException("任务文件缺少 items 数组")
        val raw = json.decodeFromJsonElement(StoreData.serializer(), root)
        require(raw.revision >= 0) { "invalid revision" }
        val ids = HashSet<String>()
        val items = raw.items.mapIndexed { index, item ->
            require(item.id.isNotBlank() && ids.add(item.id)) { "任务 ID 缺失或重复" }
            val normalized = com.deskweek.android.core.model.TaskRules.normalize(
                item,
                java.time.Instant.now().toString(),
            )
            normalized.copy(extraFields = JsonObject(array[index].jsonObject.filterKeys { it !in TASK_FIELDS }))
        }
        return raw.copy(items = items, extraFields = JsonObject(root.filterKeys { it !in STORE_FIELDS }))
    }

    /**
     * 保存并返回新版本。保存前把当前主文件滚成备份，
     * 且只有备份本身可解析时才覆盖旧备份（防止把坏文件写成"好备份"）。
     */
    @Synchronized fun save(data: StoreData): StoreData {
        dir.mkdirs()
        val current = load()
        check(current.loadError == null) { "数据及备份损坏，已阻止覆盖；请先恢复文件" }
        require(data.items.map { it.id }.toSet().size == data.items.size) { "任务 ID 重复" }
        val payload = data.copy(
            initialized = true,
            revision = Math.addExact(current.data.revision, 1),
            updatedAt = java.time.Instant.now().toString(),
        )
        val text = encode(payload)
        parse(text)

        if (file.exists()) {
            runCatching { parse(file.readText(Charsets.UTF_8)) }.onSuccess {
                writeAtomic(backupFile, file.readText(Charsets.UTF_8))
            }
        }
        writeAtomic(file, text)
        return payload
    }

    @Synchronized fun exportTo(target: File): File {
        val result = load()
        check(result.loadError == null) { "数据损坏，不能导出为空文件" }
        val current = result.data
        target.parentFile?.mkdirs()
        writeAtomic(target, encode(current))
        return target
    }

    @Synchronized fun importFrom(source: File, merge: Boolean = false): StoreData {
        val incoming = parse(source.readText(Charsets.UTF_8))
        val result = load()
        if (result.loadError != null) {
            check(!merge) { "当前数据损坏，请选择替换导入恢复" }
            val stamp = System.currentTimeMillis()
            listOf(file, backupFile).filter { it.exists() }.forEach { damaged ->
                damaged.copyTo(File(dir, "${damaged.name}.corrupt-$stamp"), overwrite = false)
            }
            val restored = incoming.copy(initialized = true, revision = 1, updatedAt = java.time.Instant.now().toString())
            writeAtomic(file, encode(restored))
            return restored
        }
        val current = result.data
        val merged = if (!merge) {
            incoming
        } else {
            val byId = current.items.associateBy { it.id }.toMutableMap()
            incoming.items.forEach { byId[it.id] = it }
            current.copy(items = byId.values.toList())
        }
        return save(merged)
    }

    private fun encode(data: StoreData): String {
        val known = json.encodeToJsonElement(StoreData.serializer(), data).jsonObject
        return JsonObject(data.extraFields + known + ("items" to JsonArray(data.items.map { task ->
            JsonObject(task.extraFields + json.encodeToJsonElement(Task.serializer(), task).jsonObject)
        }))).toString()
    }

    private val TASK_FIELDS = setOf("id", "title", "detail", "date", "time", "done", "completedAt", "deleted", "deletedAt", "color", "defer", "createdAt")
    private val STORE_FIELDS = setOf("initialized", "items", "prefs", "revision", "updatedAt")

    private fun writeAtomic(target: File, text: String) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "${target.name}.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        try {
            Files.move(
                tmp.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
            // 某些文件系统不支持 ATOMIC_MOVE，退化为普通替换
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
