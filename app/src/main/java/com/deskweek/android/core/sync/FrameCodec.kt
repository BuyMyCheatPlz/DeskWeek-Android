package com.deskweek.android.core.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** 协议错误，`code` 与 JS 侧 `ProtocolError.code` 对齐 */
class ProtocolException(val code: String, message: String) : Exception(message)

/**
 * 帧编解码：4 字节无符号大端长度 + UTF-8 JSON，单帧上限 1 MiB。
 * 与 JS 侧 `src/main/sync/frame.cjs` 行为一致。
 */
object FrameCodec {

    const val MAX_FRAME = 1024 * 1024
    private const val HEADER = 4

    data class Decoded(val messages: List<JsonObject>, val rest: ByteArray)

    fun encode(message: JsonObject): ByteArray {
        val payload = message.toString().toByteArray(Charsets.UTF_8)
        if (payload.isEmpty()) throw ProtocolException("EFRAME", "empty frame")
        if (payload.size > MAX_FRAME) throw ProtocolException("EFRAME", "frame too large: ${payload.size}")
        val out = ByteArray(HEADER + payload.size)
        out[0] = (payload.size ushr 24).toByte()
        out[1] = (payload.size ushr 16).toByte()
        out[2] = (payload.size ushr 8).toByte()
        out[3] = payload.size.toByte()
        payload.copyInto(out, HEADER)
        return out
    }

    /** 解出缓冲区中尽可能多的完整帧，返回消息与未消费的尾部 */
    fun decode(buffer: ByteArray): Decoded {
        val messages = ArrayList<JsonObject>()
        var offset = 0
        while (buffer.size - offset >= HEADER) {
            val length = readUInt32(buffer, offset)
            if (length == 0) throw ProtocolException("EFRAME", "empty frame")
            if (length < 0) throw ProtocolException("EFRAME", "unsigned frame too large")
            if (length > MAX_FRAME) throw ProtocolException("EFRAME", "frame too large: $length")
            if (buffer.size - offset - HEADER < length) break
            val body = buffer.copyOfRange(offset + HEADER, offset + HEADER + length)
            val text = String(body, Charsets.UTF_8)
            val element = runCatching { Json.parseToJsonElement(text) }
                .getOrElse { throw ProtocolException("EFRAME", "invalid json frame: ${it.message}") }
            val obj = runCatching { element.jsonObject }
                .getOrElse { throw ProtocolException("EFRAME", "frame must be a json object") }
            messages.add(obj)
            offset += HEADER + length
        }
        return Decoded(messages, buffer.copyOfRange(offset, buffer.size))
    }

    private fun readUInt32(buffer: ByteArray, offset: Int): Int {
        return ((buffer[offset].toInt() and 0xFF) shl 24) or
            ((buffer[offset + 1].toInt() and 0xFF) shl 16) or
            ((buffer[offset + 2].toInt() and 0xFF) shl 8) or
            (buffer[offset + 3].toInt() and 0xFF)
    }
}
