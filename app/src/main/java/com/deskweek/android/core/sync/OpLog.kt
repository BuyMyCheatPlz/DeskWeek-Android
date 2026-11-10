package com.deskweek.android.core.sync

import kotlinx.serialization.json.*

/** Causal operation log; field registers retain all concurrent values until explicitly resolved. */
class OpLog(val groupId: String) {
    companion object {
        val PALETTE = listOf("#9bd3c7", "#e7bd82", "#f39aa4", "#90b8ee", "#c8a6ef", "#a7d48d")
        val GROUPS = linkedMapOf(
            "title" to listOf("title"), "detail" to listOf("detail"),
            "schedule" to listOf("date", "time", "defer"),
            "done" to listOf("done", "completedAt"),
            "lifecycle" to listOf("deleted", "deletedAt"),
        )
    }
    val projection = Projection()
    private val contiguous = hashMapOf<String, Long>()
    private val pending = linkedMapOf<String, JsonObject>()
    private val history = linkedMapOf<String, JsonObject>()
    data class Gap(val from: Long, val to: Long)
    fun vectors(): Map<String, Long> = contiguous.toSortedMap()
    fun gaps(): Map<String, Gap> = pending.values.groupBy { it.text("actorId") }.mapValues { (actor, ops) ->
        Gap((contiguous[actor] ?: 0L) + 1, ops.maxOf { it.number("seq") })
    }
    fun receive(ops: List<JsonObject>): Int {
        ops.forEach { op ->
            if (op.text("groupId") != groupId) return@forEach
            val actor = op.text("actorId")
            val seq = op.number("seq")
            if (seq < 1) throw ProtocolException("ECONTRACT", "seq must be positive")
            val key = "$actor:$seq"
            val prior = history[key] ?: pending[key]
            if (prior != null && prior != op) throw ProtocolException("EIDENTITY", "conflicting operation $key")
            if (prior == null) pending[key] = op
        }
        var applied = 0
        while (true) {
            val ready = pending.values.filter { op ->
                val actor = op.text("actorId")
                op.number("seq") == (contiguous[actor] ?: 0L) + 1 &&
                    op.context().all { (dependency, seq) -> (contiguous[dependency] ?: 0L) >= seq }
            }.sortedWith(compareBy({ it.text("actorId") }, { it.number("seq") }))
            if (ready.isEmpty()) break
            ready.forEach { op ->
                val actor = op.text("actorId")
                val key = "$actor:${op.number("seq")}"
                projection.apply(op)
                history[key] = op
                pending.remove(key)
                contiguous[actor] = op.number("seq")
                applied++
            }
        }
        return applied
    }
    fun nextOp(
        actorId: String, taskId: String, kind: String, changes: JsonObject,
        context: JsonObject = buildJsonObject { vectors().forEach { (a, s) -> put(a, s) } },
        recordedAt: String,
    ): JsonObject = buildJsonObject {
        val seq = (contiguous[actorId] ?: 0L) + 1
        put("protocolVersion", 1); put("groupId", groupId); put("opId", "$actorId:$seq")
        put("actorId", actorId); put("seq", seq); put("context", context)
        put("taskId", taskId); put("kind", kind); put("changes", changes); put("recordedAt", recordedAt)
    }
    fun opsInRange(actorId: String, from: Long, to: Long): List<JsonObject> = history.values.filter {
        it.text("actorId") == actorId && it.number("seq") in from..to
    }.sortedBy { it.number("seq") }
    fun allOps(): List<JsonObject> = history.values.toList()

    class Projection {
        private data class Write(val actor: String, val seq: Long, val context: Map<String, Long>, val value: JsonObject) {
            fun observes(other: Write) = (actor == other.actor && seq > other.seq) || (context[other.actor] ?: 0) >= other.seq
        }
        private class Register {
            val history = arrayListOf<Write>()
            val frontier = arrayListOf<Write>()
        }
        private class State(val id: String) {
            val groups = hashMapOf<String, Register>()
            var createdAt = ""
            var creator: String? = null
        }
        private val tasks = hashMapOf<String, State>()
        private val tombstones = hashSetOf<String>()
        private val order = compareBy<Write>({ it.seq }, { it.actor })
        val size: Int get() = tasks.size

        fun apply(op: JsonObject) {
            val id = op.text("taskId")
            if (id in tombstones) return
            val kind = op.text("kind")
            if (kind == "purge") { tasks.remove(id); tombstones.add(id); return }
            val task = tasks[id] ?: if (kind == "create") State(id).also { tasks[id] = it } else return
            val changes = op["changes"] as? JsonObject ?: JsonObject(emptyMap())
            val actor = op.text("actorId")
            val seq = op.number("seq")
            val context = op.context()
            if (kind == "create" && (task.creator == null || actor < task.creator!!)) {
                task.creator = actor
                task.createdAt = (changes["createdAt"] as? JsonPrimitive)?.content ?: op.text("recordedAt")
            }
            val affected = when (kind) {
                "create" -> GROUPS.keys.toList()
                "patch", "resolve" -> GROUPS.filterValues { fields -> fields.any { it in changes } }.keys.toList()
                "trash", "restore" -> listOf("lifecycle")
                else -> return
            }
            affected.forEach { group ->
                val register = task.groups.getOrPut(group) { Register() }
                val marker = Write(actor, seq, context, JsonObject(emptyMap()))
                val base = register.history.filter { marker.observes(it) }.maxWithOrNull(order)?.value ?: defaults(group)
                val value = when (kind) {
                    "trash" -> buildJsonObject { put("deleted", true); put("deletedAt", changes["deletedAt"] ?: JsonPrimitive(op.text("recordedAt"))) }
                    "restore" -> buildJsonObject { put("deleted", false); put("deletedAt", "") }
                    else -> JsonObject((if (kind == "create") defaults(group) else base) + changes.filterKeys { it in GROUPS.getValue(group) })
                }
                val write = Write(actor, seq, context, value)
                register.history.add(write)
                if (register.frontier.none { it.observes(write) }) {
                    register.frontier.removeAll { write.observes(it) }
                    register.frontier.add(write)
                }
            }
        }
        private fun defaults(group: String): JsonObject = buildJsonObject {
            GROUPS.getValue(group).forEach { field ->
                when (field) {
                    "done", "deleted" -> put(field, false)
                    "defer" -> put(field, "none")
                    else -> put(field, "")
                }
            }
        }
        private fun selected(group: String, register: Register): Write? {
            val values = if (group == "lifecycle") {
                register.frontier.filter { it.value["deleted"]?.jsonPrimitive?.booleanOrNull == true }.ifEmpty { register.frontier }
            } else register.frontier
            return values.maxWithOrNull(order)
        }
        /** Original values for a conflict chooser; history is retained even when its candidate is not displayed. */
        fun conflictChoices(taskId: String, group: String): List<JsonObject> = tasks[taskId]?.groups?.get(group)?.frontier.orEmpty()
            .sortedWith(compareBy({ it.actor }, { it.seq })).map { write -> buildJsonObject {
                put("actorId", write.actor); put("seq", write.seq); put("value", write.value)
            } }
        fun snapshot(): JsonObject {
            val ordered = tasks.values.sortedBy { it.id }
            val values = ordered.associate { task ->
                task.id to task.groups.flatMap { (group, register) -> selected(group, register)?.value?.entries.orEmpty() }.associate { it.key to it.value }
            }
            val colors = hashMapOf<String, String>()
            ordered.filter { values[it.id]?.get("date")?.jsonPrimitive?.content.orEmpty().isNotBlank() }
                .groupBy { values[it.id]?.get("date")?.jsonPrimitive?.content }.values.forEach { sameDay ->
                    sameDay.forEachIndexed { index, task -> colors[task.id] = PALETTE[index % PALETTE.size] }
                }
            return buildJsonObject {
                put("tasks", buildJsonArray { ordered.forEach { task -> add(buildJsonObject {
                    put("taskId", task.id)
                    GROUPS.keys.forEach { group -> defaults(group).forEach { (key, default) -> put(key, values[task.id]?.get(key) ?: default) } }
                    put("createdAt", task.createdAt); put("color", colors[task.id] ?: PALETTE[0])
                }) } })
                put("conflicts", buildJsonArray { ordered.forEach { task ->
                    task.groups.toSortedMap().forEach { (group, register) ->
                        if (group != "lifecycle" && register.frontier.map { it.value }.distinct().size > 1) add(buildJsonObject {
                            put("taskId", task.id); put("group", group); put("winner", selected(group, register)!!.actor)
                            put("candidates", buildJsonArray { register.frontier.sortedWith(compareBy({ it.actor }, { it.seq })).forEach {
                                add(buildJsonObject { put("actorId", it.actor); put("seq", it.seq) })
                            } })
                        })
                    }
                } })
            }
        }
        fun canonical(): String = CanonicalJson.canonicalize(snapshot())
    }
}
private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.content ?: throw ProtocolException("ECONTRACT", "missing $key")
private fun JsonObject.number(key: String) = (this[key] as? JsonPrimitive)?.longOrNull ?: throw ProtocolException("ECONTRACT", "invalid $key")
private fun JsonObject.context(): Map<String, Long> = (this["context"] as? JsonObject)?.mapValues {
    it.value.jsonPrimitive.longOrNull ?: throw ProtocolException("ECONTRACT", "invalid context")
}.orEmpty()
