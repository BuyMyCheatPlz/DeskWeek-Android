package com.deskweek.android.core.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File

/**
 * 操作日志的本地持久化（P2）。
 *
 * 直接落地为 JSON 文件并原子替换：写入成功即视为「已提交」，
 * 重启后按序重放恢复 seq 与投影，不回退 seq。
 * 正式版应换成带 fsync 与校验的追加日志，见协议文档 §6。
 */
class SyncStore(private val dir: File, val groupId: String = "p0-group") {

    private val file = File(dir, "sync-ops.json")
    private val ops = LinkedHashMap<String, JsonObject>()

    val log = OpLog(groupId)

    init {
        dir.mkdirs()
        load()
    }

    fun allOps(): List<JsonObject> = ops.values.toList()

    fun receive(incoming: List<JsonObject>): Int {
        var applied = 0
        for (op in incoming) {
            val actorId = (op["actorId"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: continue
            val seq = (op["seq"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: continue
            val key = "$actorId:$seq"
            if (ops.containsKey(key)) continue
            ops[key] = op
            applied++
        }
        if (applied > 0) {
            log.receive(ops.values.toList())
            persist()
        }
        return applied
    }

    fun vectors(): Map<String, Long> = log.vectors()

    fun projectionCanonical(): String = log.projection.canonical()

    private fun load() {
        if (!file.exists()) return
        runCatching {
            val parsed = Json.parseToJsonElement(file.readText()).jsonObject
            val array = parsed["ops"] as? kotlinx.serialization.json.JsonArray ?: return
            array.forEach { element ->
                val op = element.jsonObject
                val actorId = (op["actorId"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return@forEach
                val seq = (op["seq"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return@forEach
                ops["$actorId:$seq"] = op
            }
            log.receive(ops.values.toList())
        }
    }

    private fun persist() {
        val payload = kotlinx.serialization.json.buildJsonObject {
            put("groupId", kotlinx.serialization.json.JsonPrimitive(groupId))
            put(
                "ops",
                kotlinx.serialization.json.buildJsonArray { ops.values.forEach { add(it) } },
            )
        }
        val tmp = File(dir, "sync-ops.json.tmp")
        tmp.writeText(payload.toString())
        try {
            java.nio.file.Files.move(
                tmp.toPath(), file.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (e: Exception) {
            file.writeText(payload.toString())
            tmp.delete()
        }
    }
}
