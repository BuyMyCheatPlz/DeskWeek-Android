package com.deskweek.android.core.data

import com.deskweek.android.core.model.StoreData
import com.deskweek.android.core.model.Task
import kotlinx.serialization.json.Json
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
            isLenient = true
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

    fun load(): LoadResult {
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
                save(data)
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
        val raw = json.decodeFromString<StoreData>(text)
        if (raw.items.any { it.id.isBlank() || it.title.isBlank() && it.title != "未命名事项" }) {
            // 与桌面版一致：id/title 是硬性要求，缺了就是坏文件
            if (raw.items.any { it.id.isBlank() }) throw IllegalArgumentException("invalid item: missing id")
        }
        val ids = HashSet<String>()
        val items = raw.items.map { item ->
            val normalized = com.deskweek.android.core.model.TaskRules.normalize(
                item,
                java.time.Instant.now().toString(),
            )
            // ID 重复时重新生成，避免后续按 id 查找串台
            if (!ids.add(normalized.id)) normalized.copy(id = java.util.UUID.randomUUID().toString())
            else normalized
        }
        return raw.copy(items = items)
    }

    /**
     * 保存并返回新版本。保存前把当前主文件滚成备份，
     * 且只有备份本身可解析时才覆盖旧备份（防止把坏文件写成"好备份"）。
     */
    fun save(data: StoreData): StoreData {
        dir.mkdirs()
        val payload = data.copy(
            initialized = true,
            revision = data.revision + 1,
            updatedAt = java.time.Instant.now().toString(),
        )
        val text = json.encodeToString(StoreData.serializer(), payload)

        if (file.exists()) {
            runCatching { parse(file.readText(Charsets.UTF_8)) }.onSuccess {
                writeAtomic(backupFile, file.readText(Charsets.UTF_8))
            }
        }
        writeAtomic(file, text)
        return payload
    }

    fun exportTo(target: File): File {
        val current = load().data
        target.parentFile?.mkdirs()
        writeAtomic(target, json.encodeToString(StoreData.serializer(), current))
        return target
    }

    fun importFrom(source: File, merge: Boolean = false): StoreData {
        val incoming = parse(source.readText(Charsets.UTF_8))
        val current = load().data
        val merged = if (!merge) {
            incoming
        } else {
            val byId = current.items.associateBy { it.id }.toMutableMap()
            incoming.items.forEach { byId[it.id] = it }
            current.copy(items = byId.values.toList())
        }
        return save(merged)
    }

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
        } catch (e: Exception) {
            // 某些文件系统不支持 ATOMIC_MOVE，退化为普通替换
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
