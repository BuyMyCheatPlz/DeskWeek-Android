package com.deskweek.android.core.sync

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * 协议消息校验（protocolVersion 1），结构+类型层面，与 JS 侧
 * `src/main/sync/messages.cjs` 保持一致；签名与业务语义留给 P2。
 */
object SyncMessages {

    const val VERSION = 1
    const val MAX_TEXT = 4096
    const val MAX_ID = 128
    const val MAX_OPS = 100

    val TYPES = setOf("HELLO", "SUMMARY", "WANT", "OPS", "ACK", "CHANGED", "PING", "PONG", "ERROR")
    val KINDS = setOf("create", "patch", "trash", "restore", "purge", "resolve")

    private val CHANGE_TEXT_FIELDS =
        setOf("title", "detail", "date", "time", "defer", "completedAt", "deletedAt")
    private val CHANGE_BOOL_FIELDS = setOf("done", "deleted")

    fun validate(message: JsonObject) {
        val type = (message["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw ProtocolException("ECONTRACT", "message must have a string type")
        if (type !in TYPES) throw ProtocolException("ECONTRACT", "unknown message type: $type")

        val version = (message["protocolVersion"] as? JsonPrimitive)?.intOrNull
        if (version != VERSION) throw ProtocolException("EPROTO", "unsupported protocolVersion: $version")

        when (type) {
            "HELLO" -> {
                requireId(message, "groupId")
                requireId(message, "actorId")
                requireText(message, "nonce", 256)
                val caps = message["capabilities"]
                if (caps !is JsonArray) throw ProtocolException("ECONTRACT", "capabilities must be an array")
            }
            "SUMMARY", "ACK" -> requireVectors(message, "vectors")
            "WANT" -> {
                requireId(message, "actorId")
                val from = requireSeq(message, "from")
                val to = requireSeq(message, "to")
                if (to < from) throw ProtocolException("ECONTRACT", "to must be >= from")
            }
            "OPS" -> {
                val ops = message["ops"]
                if (ops !is JsonArray || ops.isEmpty()) {
                    throw ProtocolException("ECONTRACT", "ops must be a non-empty array")
                }
                if (ops.size > MAX_OPS) throw ProtocolException("ECONTRACT", "ops batch exceeds $MAX_OPS")
                ops.forEach { validateOp(it) }
            }
            "CHANGED" -> requireVectors(message, "upto")
            "PING", "PONG" -> requireText(message, "nonce", 256)
            "ERROR" -> {
                requireText(message, "code", 64)
                requireText(message, "message")
            }
        }
    }

    fun validateOp(element: JsonElement) {
        val op = element as? JsonObject ?: throw ProtocolException("ECONTRACT", "op must be an object")

        val actorId = requireId(op, "actorId")
        val seq = requireSeq(op, "seq")
        if (seq < 1) throw ProtocolException("ECONTRACT", "op.seq starts at 1")

        val opId = requireText(op, "opId")
        if (opId != "$actorId:$seq") throw ProtocolException("ECONTRACT", "op.opId must be actorId:seq")

        requireId(op, "groupId")
        requireId(op, "taskId")

        val kind = requireText(op, "kind")
        if (kind !in KINDS) throw ProtocolException("ECONTRACT", "op.kind unsupported: $kind")

        val changes = op["changes"] as? JsonObject
            ?: throw ProtocolException("ECONTRACT", "op.changes must be an object")
        for ((field, raw) in changes) {
            val primitive = raw as? JsonPrimitive
                ?: throw ProtocolException("ECONTRACT", "op.changes.$field must be a primitive")
            when (field) {
                in CHANGE_TEXT_FIELDS -> if (!primitive.isString) {
                    throw ProtocolException("ECONTRACT", "op.changes.$field must be a string")
                }
                in CHANGE_BOOL_FIELDS -> if (primitive.booleanOrNull == null) {
                    throw ProtocolException("ECONTRACT", "op.changes.$field must be boolean")
                }
                else -> throw ProtocolException("ECONTRACT", "op.changes unknown field: $field")
            }
        }

        requireVectors(op, "context")
        requireText(op, "recordedAt")
        requireText(op, "signature")
    }

    private fun requireId(obj: JsonObject, field: String): String {
        val value = requireText(obj, field)
        if (value.isEmpty() || value.length > MAX_ID) {
            throw ProtocolException("ECONTRACT", "$field must be a non-empty id")
        }
        return value
    }

    private fun requireText(obj: JsonObject, field: String, max: Int = MAX_TEXT): String {
        val primitive = obj[field] as? JsonPrimitive
        if (primitive == null || !primitive.isString) {
            throw ProtocolException("ECONTRACT", "$field must be a string")
        }
        val value = primitive.content
        if (value.length > max) throw ProtocolException("ECONTRACT", "$field must be a string <= $max")
        return value
    }

    private fun requireSeq(obj: JsonObject, field: String): Long {
        val value = (obj[field] as? JsonPrimitive)?.longOrNull
            ?: throw ProtocolException("ECONTRACT", "$field must be a non-negative integer")
        if (value < 0) throw ProtocolException("ECONTRACT", "$field must be a non-negative integer")
        return value
    }

    private fun requireVectors(obj: JsonObject, field: String) {
        val vectors = obj[field] as? JsonObject
            ?: throw ProtocolException("ECONTRACT", "$field must be an object")
        for ((key, raw) in vectors) {
            if (key.isEmpty() || key.length > MAX_ID) {
                throw ProtocolException("ECONTRACT", "$field key must be a non-empty id")
            }
            val value = (raw as? JsonPrimitive)?.longOrNull
                ?: throw ProtocolException("ECONTRACT", "$field.$key must be a non-negative integer")
            if (value < 0) throw ProtocolException("ECONTRACT", "$field.$key must be a non-negative integer")
        }
    }
}
