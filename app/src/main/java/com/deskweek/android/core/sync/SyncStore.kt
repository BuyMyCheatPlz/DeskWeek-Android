package com.deskweek.android.core.sync

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.json.*

/** Durable protocol history. Validate and persist a candidate before publishing its vectors. */
class SyncStore(private val dir: File, val groupId: String = "p0-group") {
    companion object {
        private val locks = java.util.concurrent.ConcurrentHashMap<String, Any>()
    }
    private val lock = locks.computeIfAbsent(dir.canonicalPath) { Any() }
    private val file = File(dir, "sync-ops.json")
    private var ops = linkedMapOf<String, JsonObject>()
    var log = OpLog(groupId)
        private set

    init {
        dir.mkdirs()
        synchronized(lock) { reload() }
    }

    private fun reload() {
        if (file.exists()) {
            try {
                val root = Json.parseToJsonElement(file.readText()).jsonObject
                check(root["groupId"]?.jsonPrimitive?.content == groupId) { "同步数据集不匹配" }
                val saved = root["ops"] as? JsonArray ?: error("缺少同步历史")
                ops = validated(saved.map { it.jsonObject }, linkedMapOf())
                log = OpLog(groupId).also { it.receive(ops.values.toList()) }
            } catch (e: Exception) {
                throw IllegalStateException("同步历史无法恢复，已阻止覆盖", e)
            }
        }
    }

    fun allOps(): List<JsonObject> = synchronized(lock) { reload(); ops.values.toList() }
    fun vectors(): Map<String, Long> = synchronized(lock) { reload(); log.vectors() }
    fun projectionCanonical(): String = synchronized(lock) { reload(); log.projection.canonical() }

    fun receive(incoming: List<JsonObject>): Int = synchronized(lock) {
        reload()
        val candidate = validated(incoming, LinkedHashMap(ops))
        val added = candidate.size - ops.size
        if (added == 0) return@synchronized 0
        val candidateLog = OpLog(groupId).also { it.receive(candidate.values.toList()) }
        persist(candidate)
        ops = candidate
        log = candidateLog
        added
    }

    private fun validated(incoming: List<JsonObject>, target: LinkedHashMap<String, JsonObject>): LinkedHashMap<String, JsonObject> {
        incoming.forEach { op ->
            SyncMessages.validateOp(op)
            if (op["groupId"]!!.jsonPrimitive.content != groupId) {
                throw ProtocolException("EGROUP", "同步操作属于其他数据集")
            }
            val key = op["opId"]!!.jsonPrimitive.content
            val previous = target[key]
            if (previous != null && previous != op) {
                throw ProtocolException("EIDENTITY", "相同操作 ID 对应不同内容：$key")
            }
            target[key] = op
        }
        return target
    }

    private fun persist(candidate: Map<String, JsonObject>) {
        val payload = buildJsonObject {
            put("groupId", groupId)
            put("ops", JsonArray(candidate.values.toList()))
        }.toString()
        val tmp = File(dir, "sync-ops.json.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(payload.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
