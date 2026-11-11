package com.deskweek.android.core.transfer

import kotlinx.serialization.json.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Base64
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

/** Offline JSON transport. Single QR contains raw JSON; multipart QR contains a JSON envelope. */
object JsonQr {
    const val FORMAT = "deskweek-json-qr"
    const val MAX_BYTES = 1048576
    const val QR_BYTES = 650
    const val CHUNK_BYTES = 300
    const val MAX_PARTS = (MAX_BYTES + CHUNK_BYTES - 1) / CHUNK_BYTES
    const val COMPRESSED_CHUNK_BYTES = 600
    private const val MAX_COMPRESSED_PARTS = (MAX_BYTES + COMPRESSED_CHUNK_BYTES - 1) / COMPRESSED_CHUNK_BYTES
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    fun validate(text: String): JsonObject {
        require(text.toByteArray().size <= MAX_BYTES) { "JSON 超过 1 MiB，请使用文件导入" }
        val value = Json.parseToJsonElement(text) as? JsonObject ?: error("JSON 不是对象")
        val items = value["items"] as? JsonArray ?: error("没有有效的 items 任务数组")
        val ids = hashSetOf<String>()
        items.forEach { entry ->
            val task = entry as? JsonObject ?: error("任务格式无效")
            fun string(k: String) = (task[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val id = string("id")
            require(!id.isNullOrBlank() && ids.add(id) && !string("title").isNullOrBlank()) { "任务 ID 缺失、重复或标题无效" }
            listOf("detail", "date", "time", "defer", "completedAt", "deletedAt", "createdAt", "color").forEach {
                require(it !in task || string(it) != null) { "任务 $it 类型无效" }
            }
            listOf("done", "deleted").forEach {
                require(it !in task || (task[it] as? JsonPrimitive)?.let { raw -> !raw.isString && raw.booleanOrNull != null } == true) { "任务 $it 类型无效" }
            }
            val date = string("date").orEmpty(); val time = string("time").orEmpty()
            require(date.isEmpty() || com.deskweek.android.core.model.TaskRules.isValidDateKey(date)) { "任务日期无效" }
            require(time.isEmpty() || com.deskweek.android.core.model.TaskRules.isValidTime(time)) { "任务时间无效" }
        }
        require("prefs" !in value || value["prefs"] is JsonObject) { "prefs 格式无效" }
        return value
    }
    private fun legacyFrames(text: String): List<String> {
        val compact = validate(text).toString()
        val bytes = compact.toByteArray(Charsets.UTF_8)
        if (bytes.size <= QR_BYTES) return listOf(compact)
        val sha = hash(bytes)
        val parts = bytes.toList().chunked(CHUNK_BYTES)
        return parts.mapIndexed { index, part -> buildJsonObject {
            put("format", FORMAT); put("version", 1); put("id", sha.take(16)); put("part", index + 1)
            put("total", parts.size); put("bytes", bytes.size); put("sha256", sha)
            put("data", Base64.getEncoder().encodeToString(part.toByteArray()))
        }.toString() }
    }
    fun frames(text: String, legacy: Boolean = false): List<String> {
        val compact = validate(text).toString(); val raw = compact.toByteArray(Charsets.UTF_8)
        if (legacy) return legacyFrames(compact)
        val oldCount = if (raw.size <= QR_BYTES) 1 else (raw.size + CHUNK_BYTES - 1) / CHUNK_BYTES
        val deflater = Deflater()
        val output = ByteArrayOutputStream()
        try {
            deflater.setInput(raw); deflater.finish(); val buffer = ByteArray(4096)
            while (!deflater.finished()) { val count = deflater.deflate(buffer); check(count > 0); output.write(buffer, 0, count) }
        } finally { deflater.end() }
        val zipped = output.toByteArray()
        if (zipped.size > MAX_BYTES) return legacyFrames(compact)
        val sha = hash(zipped); val parts = zipped.toList().chunked(COMPRESSED_CHUNK_BYTES)
        val result = parts.mapIndexed { index, part -> buildJsonObject {
            put("f", "DWQ"); put("v", 2); put("p", index + 1); put("n", parts.size)
            put("b", zipped.size); put("u", raw.size); put("h", sha)
            put("d", Base64.getEncoder().encodeToString(part.toByteArray()))
        }.toString() }
        if (result.size < oldCount) return result
        val old = legacyFrames(compact)
        return if (result.size == oldCount && result.sumOf { it.toByteArray().size } < old.sumOf { it.toByteArray().size }) result else old
    }
    data class Result(val received: Int, val total: Int, val text: String? = null)
    class Collector {
        private var meta: String? = null
        private var total = 0
        private val parts = hashMapOf<Int, ByteArray>()
        val progress get() = Result(parts.size, total)
        fun reset() { meta = null; total = 0; parts.clear() }
        fun accept(text: String): Result {
            require(text.toByteArray().size <= 2048) { "二维码内容过大或不是 DeskWeek 数据" }
            val p = Json.parseToJsonElement(text) as? JsonObject ?: error("二维码不是 JSON 对象")
            if (p["f"] == JsonPrimitive("DWQ")) return acceptCompressed(p)
            if (p["format"] != JsonPrimitive(FORMAT)) {
                check(meta == null) { "正在接收另一份数据，请先清空进度" }
                validate(text); return Result(1, 1, text)
            }
            fun number(k: String) = (p[k] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull ?: error("分片 $k 无效")
            fun string(k: String) = (p[k] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("分片 $k 无效")
            val count = number("total"); val part = number("part"); val size = number("bytes"); val sha = string("sha256")
            require(number("version") == 1 && count in 2..MAX_PARTS && part in 1..count && size in (QR_BYTES + 1)..MAX_BYTES && count == (size + CHUNK_BYTES - 1) / CHUNK_BYTES && sha.matches(Regex("[a-f0-9]{64}")) && string("id") == sha.take(16)) { "二维码分片格式无效" }
            val content = string("data")
            val bytes = Base64.getDecoder().decode(content)
            require(Base64.getEncoder().encodeToString(bytes) == content && bytes.size == if (part == count) size - (count - 1) * CHUNK_BYTES else CHUNK_BYTES) { "二维码分片长度无效" }
            val nextMeta = "${sha.take(16)}:$count:$size:$sha"
            check(meta == null || meta == nextMeta) { "扫到了另一份数据，请先清空进度" }
            check(parts[part] == null || parts[part]!!.contentEquals(bytes)) { "重复分片内容冲突，请重新扫描" }
            meta = nextMeta; total = count; parts[part] = bytes
            if (parts.size != count) return progress
            val all = ByteArray(size); var offset = 0
            (1..count).forEach { index -> parts.getValue(index).let { it.copyInto(all, offset); offset += it.size } }
            require(hash(all) == sha) { "完整性校验失败，请清空后重扫" }
            val decoded = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(all)).toString()
            validate(decoded); return Result(parts.size, count, decoded)
        }
        private fun acceptCompressed(p: JsonObject): Result {
            fun number(k: String) = (p[k] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull ?: error("分片 $k 无效")
            fun string(k: String) = (p[k] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("分片 $k 无效")
            require(number("v") == 2) { "二维码版本不兼容，请更新两端应用" }
            val count = number("n"); val part = number("p"); val size = number("b"); val rawSize = number("u"); val sha = string("h")
            require(count in 1..MAX_COMPRESSED_PARTS && part in 1..count && size in 1..MAX_BYTES && rawSize in 1..MAX_BYTES && count == (size + COMPRESSED_CHUNK_BYTES - 1) / COMPRESSED_CHUNK_BYTES && sha.matches(Regex("[a-f0-9]{64}"))) { "压缩二维码格式无效" }
            val content = string("d"); val bytes = Base64.getDecoder().decode(content)
            require(Base64.getEncoder().encodeToString(bytes) == content && bytes.size == if (part == count) size - (count - 1) * COMPRESSED_CHUNK_BYTES else COMPRESSED_CHUNK_BYTES) { "二维码分片长度无效" }
            val nextMeta = "v2:$count:$size:$rawSize:$sha"
            check(meta == null || meta == nextMeta) { "扫到了另一份数据，请先清空进度" }
            check(parts[part] == null || parts[part]!!.contentEquals(bytes)) { "重复分片内容冲突，请重新扫描" }
            meta = nextMeta; total = count; parts[part] = bytes
            if (parts.size != count) return progress
            val zipped = ByteArray(size); var offset = 0
            (1..count).forEach { index -> parts.getValue(index).let { it.copyInto(zipped, offset); offset += it.size } }
            require(hash(zipped) == sha) { "完整性校验失败，请清空后重扫" }
            val inflater = Inflater(); val output = ByteArrayOutputStream()
            try {
                inflater.setInput(zipped); val buffer = ByteArray(4096)
                while (!inflater.finished()) {
                    val written = inflater.inflate(buffer)
                    require(output.size() + written <= rawSize) { "解压数据超过声明长度" }
                    if (written > 0) output.write(buffer, 0, written)
                    else require(inflater.finished()) { "压缩数据不完整或格式无效" }
                }
                require(inflater.remaining == 0 && output.size() == rawSize) { "解压数据长度不符" }
            } finally { inflater.end() }
            val decoded = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(output.toByteArray())).toString()
            validate(decoded); return Result(parts.size, count, decoded)
        }
    }
}
