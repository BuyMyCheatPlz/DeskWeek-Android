package com.deskweek.android.core.sync

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * P2 正确性核心：操作日志 + 合并投影，与 JS 侧 `src/main/sync/oplog.cjs` 对齐。
 *
 * - 唯一键 (groupId, actorId, seq)，重复接收幂等
 * - seq 必须连续，缺前序先缓冲，补齐后按序推进
 * - 字段组按业务原子合并，并发记冲突并按 (seq, actorId) 确定性取临时值
 * - trash 优先于并发 restore；purge 终结 taskId
 * - 同日分色是确定性投影：按 taskId 升序取色板
 */
class OpLog(val groupId: String) {

    companion object {
        val PALETTE = listOf("#9bd3c7", "#e7bd82", "#f39aa4", "#90b8ee", "#c8a6ef", "#a7d48d")

        /** 字段组 → 字段名，顺序与 JS 一致 */
        val GROUPS: LinkedHashMap<String, List<String>> = linkedMapOf(
            "title" to listOf("title"),
            "detail" to listOf("detail"),
            "schedule" to listOf("date", "time", "defer"),
            "done" to listOf("done", "completedAt"),
            "lifecycle" to listOf("deleted", "deletedAt"),
        )
    }

    val projection = Projection()

    private val seen = HashSet<String>()
    private val contiguous = HashMap<String, Long>()
    private val buffer = HashMap<String, HashMap<Long, JsonObject>>()
    private val history = LinkedHashMap<String, JsonObject>()

    fun vectors(): Map<String, Long> = contiguous.toSortedMap()

    fun gaps(): Map<String, Gap> = buffer.mapValues { (actorId, pending) ->
        Gap(from = (contiguous[actorId] ?: 0L) + 1, to = pending.keys.max())
    }

    data class Gap(val from: Long, val to: Long)

    /** 返回本次真正应用的条数 */
    fun receive(ops: List<JsonObject>): Int {
        var applied = 0
        for (op in ops) {
            if (op.string("groupId") != groupId) continue
            val actorId = op.string("actorId") ?: continue
            val seq = op.longOrNull("seq") ?: continue
            val key = "$actorId:$seq"
            if (key in seen) continue
            val expected = (contiguous[actorId] ?: 0L) + 1
            when {
                seq == expected -> {
                    applyOne(op, actorId, seq)
                    applied++
                    drain(actorId)
                }
                seq > expected -> {
                    buffer.getOrPut(actorId) { HashMap() }[seq] = op
                }
            }
        }
        return applied
    }

    fun nextOp(
        actorId: String,
        taskId: String,
        kind: String,
        changes: JsonObject,
        context: JsonObject = JsonObject(emptyMap()),
        recordedAt: String,
    ): JsonObject {
        val seq = (contiguous[actorId] ?: 0L) + 1
        return buildJsonObject {
            put("protocolVersion", 1)
            put("groupId", groupId)
            put("opId", "$actorId:$seq")
            put("actorId", actorId)
            put("seq", seq)
            put("context", context)
            put("taskId", taskId)
            put("kind", kind)
            put("changes", changes)
            put("recordedAt", recordedAt)
        }
    }

    private fun drain(actorId: String) {
        val pending = buffer[actorId] ?: return
        var next = (contiguous[actorId] ?: 0L) + 1
        while (pending.containsKey(next)) {
            applyOne(pending.remove(next)!!, actorId, next)
            next++
        }
        if (pending.isEmpty()) buffer.remove(actorId)
    }

    /** 取某作者 [from, to] 区间的原始操作，供 WANT 补齐 */
    fun opsInRange(actorId: String, from: Long, to: Long): List<JsonObject> {
        val out = ArrayList<JsonObject>()
        var seq = from
        while (seq <= to) {
            history["$actorId:$seq"]?.let { out.add(it) }
            seq++
        }
        return out
    }

    fun allOps(): List<JsonObject> = history.values.toList()

    private fun applyOne(op: JsonObject, actorId: String, seq: Long) {
        projection.apply(op)
        seen.add("$actorId:$seq")
        history["$actorId:$seq"] = op
        contiguous[actorId] = seq
    }

    // ---------- 投影 ----------

    class Projection {

        private data class LastWrite(val actorId: String, val seq: Long)

        class Conflict(
            val taskId: String,
            val group: String,
            val winner: String,
            val candidates: List<Pair<String, Long>>,
        )

        private class MutableTask(val taskId: String) {
            var title: String = ""
            var detail: String = ""
            var date: String = ""
            var time: String = ""
            var defer: String = "none"
            var done: Boolean = false
            var completedAt: String = ""
            var deleted: Boolean = false
            var deletedAt: String = ""
            var createdAt: String = ""
            val meta = HashMap<String, LastWrite>()

            fun toJson(color: String): JsonObject = buildJsonObject {
                put("taskId", taskId)
                put("title", title)
                put("detail", detail)
                put("date", date)
                put("time", time)
                put("defer", defer)
                put("done", done)
                put("completedAt", completedAt)
                put("deleted", deleted)
                put("deletedAt", deletedAt)
                put("createdAt", createdAt)
                put("color", color)
            }
        }

        private val tasks = HashMap<String, MutableTask>()
        private val conflicts = ArrayList<Conflict>()
        private val tombstones = HashSet<String>()

        val size: Int get() = tasks.size

        fun apply(op: JsonObject) {
            val taskId = op.string("taskId") ?: return
            val kind = op.string("kind") ?: return
            val changes = op.objOrNull("changes") ?: JsonObject(emptyMap())
            val context = op.objOrNull("context") ?: JsonObject(emptyMap())
            val actorId = op.string("actorId") ?: return
            val seq = op.longOrNull("seq") ?: return
            val recordedAt = op.string("recordedAt") ?: ""

            if (taskId in tombstones) return
            if (kind == "purge") {
                tasks.remove(taskId)
                tombstones.add(taskId)
                return
            }

            val existing = tasks[taskId]
            if (existing == null && kind != "create") return
            val task = existing ?: MutableTask(taskId).also { tasks[taskId] = it }

            when (kind) {
                "create" -> {
                    task.title = changes.string("title") ?: ""
                    task.detail = changes.string("detail") ?: ""
                    task.date = changes.string("date") ?: ""
                    task.time = changes.string("time") ?: ""
                    task.defer = changes.string("defer") ?: "none"
                    task.done = changes.booleanOrNull("done") ?: false
                    task.completedAt = changes.string("completedAt") ?: ""
                    task.deleted = changes.booleanOrNull("deleted") ?: false
                    task.deletedAt = changes.string("deletedAt") ?: ""
                    task.createdAt = changes.string("createdAt") ?: recordedAt
                    GROUPS.keys.forEach { group -> task.meta[group] = LastWrite(actorId, seq) }
                }
                "patch" -> groupsOf(changes).forEach { group ->
                    writeGroup(task, group, changes, actorId, seq, context)
                }
                "trash" -> {
                    val last = task.meta["lifecycle"]
                    val concurrent = last != null && !observed(context, last.actorId, last.seq)
                    if (concurrent) {
                        conflicts.add(
                            Conflict(
                                taskId, "lifecycle", "trash",
                                listOf(last.actorId to last.seq, actorId to seq),
                            ),
                        )
                    }
                    task.deleted = true
                    task.deletedAt = changes.string("deletedAt") ?: recordedAt
                    task.meta["lifecycle"] = LastWrite(actorId, seq)
                }
                "restore" -> {
                    val last = task.meta["lifecycle"]
                    if (last != null && !observed(context, last.actorId, last.seq)) return
                    task.deleted = false
                    task.deletedAt = ""
                    task.meta["lifecycle"] = LastWrite(actorId, seq)
                }
            }
        }

        private fun writeGroup(
            task: MutableTask,
            group: String,
            changes: JsonObject,
            actorId: String,
            seq: Long,
            context: JsonObject,
        ) {
            val last = task.meta[group]
            val concurrent = last != null && !observed(context, last.actorId, last.seq)
            if (concurrent && last != null) {
                val incomingWins = seq > last.seq || (seq == last.seq && actorId > last.actorId)
                conflicts.add(
                    Conflict(
                        task.taskId, group,
                        if (incomingWins) actorId else last.actorId,
                        listOf(last.actorId to last.seq, actorId to seq),
                    ),
                )
                if (!incomingWins) return
            }
            GROUPS[group]!!.forEach { field ->
                if (changes.containsKey(field)) applyField(task, field, changes[field]!!)
            }
            task.meta[group] = LastWrite(actorId, seq)
        }

        private fun applyField(task: MutableTask, field: String, value: JsonElement) {
            val primitive = value as? JsonPrimitive ?: return
            when (field) {
                "title" -> task.title = primitive.content
                "detail" -> task.detail = primitive.content
                "date" -> task.date = primitive.content
                "time" -> task.time = primitive.content
                "defer" -> task.defer = primitive.content
                "completedAt" -> task.completedAt = primitive.content
                "deletedAt" -> task.deletedAt = primitive.content
                "done" -> task.done = primitive.booleanOrNull ?: false
                "deleted" -> task.deleted = primitive.booleanOrNull ?: false
                "createdAt" -> task.createdAt = primitive.content
            }
        }

        fun snapshot(): JsonObject {
            val ordered = tasks.values.sortedBy { it.taskId }
            val byDate = ordered.filter { it.date.isNotEmpty() }.groupBy { it.date }
            val colors = HashMap<String, String>()
            byDate.forEach { (_, list) ->
                list.sortedBy { it.taskId }.forEachIndexed { index, task ->
                    colors[task.taskId] = PALETTE[index % PALETTE.size]
                }
            }

            val taskArray = buildJsonArray {
                ordered.forEach { add(it.toJson(colors[it.taskId] ?: PALETTE[0])) }
            }

            val conflictArray = buildJsonArray {
                conflicts
                    .sortedWith(compareBy({ it.taskId }, { it.group }))
                    .forEach { conflict ->
                        add(buildJsonObject {
                            put("taskId", conflict.taskId)
                            put("group", conflict.group)
                            put("winner", conflict.winner)
                            put("candidates", buildJsonArray {
                                conflict.candidates.forEach { (actor, seq) ->
                                    add(buildJsonObject {
                                        put("actorId", actor)
                                        put("seq", seq)
                                    })
                                }
                            })
                        })
                    }
            }

            return buildJsonObject {
                put("tasks", taskArray)
                put("conflicts", conflictArray)
            }
        }

        fun canonical(): String = CanonicalJson.canonicalize(snapshot())
    }
}

    private fun groupsOf(changes: JsonObject): List<String> =
        OpLog.GROUPS.filterValues { fields -> fields.any { changes.containsKey(it) } }.keys.toList()

    private fun observed(context: JsonObject, actorId: String, seq: Long): Boolean {
        val value = (context[actorId] as? JsonPrimitive)?.longOrNull ?: return false
        return value >= seq
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.longOrNull(key: String): Long? =
        (this[key] as? JsonPrimitive)?.longOrNull

    private fun JsonObject.objOrNull(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.booleanOrNull(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.booleanOrNull
